/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.scheduler

import scala.util.{Failure, Success, Try}

import org.apache.spark.{SparkEnv, Logging}
import org.apache.spark.streaming.{Checkpoint, CheckpointWriter, Time}
import org.apache.spark.streaming.util.RecurringTimer
import org.apache.spark.util.{Utils, Clock, EventLoop, ManualClock}

/** Event classes for JobGenerator */
private[scheduler] sealed trait JobGeneratorEvent
private[scheduler] case class GenerateJobs(time: Time) extends JobGeneratorEvent
private[scheduler] case class ClearMetadata(time: Time) extends JobGeneratorEvent
private[scheduler] case class DoCheckpoint(
    time: Time, clearCheckpointDataLater: Boolean) extends JobGeneratorEvent
private[scheduler] case class ClearCheckpointData(time: Time) extends JobGeneratorEvent

/**
 * This class generates jobs from DStreams as well as drives checkpointing and cleaning
 * up DStream metadata.
 */
private[streaming]
class JobGenerator(jobScheduler: JobScheduler) extends Logging {

  private val ssc = jobScheduler.ssc
  private val conf = ssc.conf
  private val graph = ssc.graph

  val clock = {
    val clockClass = ssc.sc.conf.get(
      "spark.streaming.clock", "org.apache.spark.util.SystemClock")
    try {
      Utils.classForName(clockClass).newInstance().asInstanceOf[Clock]
    } catch {
      case e: ClassNotFoundException if clockClass.startsWith("org.apache.spark.streaming") =>
        val newClockClass = clockClass.replace("org.apache.spark.streaming", "org.apache.spark")
        Utils.classForName(newClockClass).newInstance().asInstanceOf[Clock]
    }
  }

  //longTime => eventLoop.post(GenerateJobs(new Time(longTime))) is callback
  private val timer = new RecurringTimer(clock, ssc.graph.batchDuration.milliseconds,
    longTime => eventLoop.post(GenerateJobs(new Time(longTime))), "JobGenerator")

  // This is marked lazy so that this is initialized after checkpoint duration has been set
  // in the context and the generator has been started.
  private lazy val shouldCheckpoint = ssc.checkpointDuration != null && ssc.checkpointDir != null

  private lazy val checkpointWriter = if (shouldCheckpoint) {
    new CheckpointWriter(this, ssc.conf, ssc.checkpointDir, ssc.sparkContext.hadoopConfiguration)
  } else {
    null
  }

  // eventLoop is created when generator starts.
  // This not being null means the scheduler has been started and not stopped
  private var eventLoop: EventLoop[JobGeneratorEvent] = null

  // last batch whose completion,checkpointing and metadata cleanup has been completed
  private var lastProcessedBatch: Time = null

  /** Start generation of jobs */
  def start(): Unit = synchronized {
    if (eventLoop != null) return // generator has already been started

    // Call checkpointWriter here to initialize it before eventLoop uses it to avoid a deadlock.
    // See SPARK-10125
    checkpointWriter

    /**
     * 每隔一段时间RecurringTimer会生成一个JobGeneratorEvent(既GenerateJobs)，
     * 并将此事件JobGeneratorEvent放入到EventLoop类中
     * 维护的eventQueue（既java.util.concurrent.LinkedBlockingDeque）队列中,
     * EventLoop会启动一个线程，当有事件放入此队列时，
     * 执行回调函数onReceive，也就是JobGenerator中的processEvent，
     * 进而执行generateJobs方法，进而调用graph.generateJobs(time)
     * generateJobs方法会生成Seq[Job]既JobSet，该JobSet中包含完整的blocks数据块和RDD DAG实例（运算的逻辑）
     * generateJobs方法中生成JobSet成功后，会调用jobScheduler.submitJobSet(JobSet(time, jobs, streamIdToInputInfos))
     *
     * 注意:
     * graph.generateJobs(time)过程，一次batch产生一个Seq[Job}，里面可能包含多个 Job —— 所以，确切的，有几个output操作，就调用几次ForEachDStream.generatorJob(time)，就产生出几个Job
     */

    eventLoop = new EventLoop[JobGeneratorEvent]("JobGenerator") {
      override protected def onReceive(event: JobGeneratorEvent): Unit = processEvent(event)

      override protected def onError(e: Throwable): Unit = {
        jobScheduler.reportError("Error in job generator", e)
      }
    }
    eventLoop.start()

    if (ssc.isCheckpointPresent) {
      restart()
    } else {
      startFirstTime()
    }
  }

  /**
   * Stop generation of jobs. processReceivedData = true makes this wait until jobs
   * of current ongoing time interval has been generated, processed and corresponding
   * checkpoints written.
   */
  def stop(processReceivedData: Boolean): Unit = synchronized {
    if (eventLoop == null) return // generator has already been stopped

    if (processReceivedData) {
      logInfo("Stopping JobGenerator gracefully")
      val timeWhenStopStarted = System.currentTimeMillis()
      val stopTimeoutMs = conf.getTimeAsMs(
        "spark.streaming.gracefulStopTimeout", s"${10 * ssc.graph.batchDuration.milliseconds}ms")
      val pollTime = 100

      // To prevent graceful stop to get stuck permanently
      def hasTimedOut: Boolean = {
        val timedOut = (System.currentTimeMillis() - timeWhenStopStarted) > stopTimeoutMs
        if (timedOut) {
          logWarning("Timed out while stopping the job generator (timeout = " + stopTimeoutMs + ")")
        }
        timedOut
      }

      // Wait until all the received blocks in the network input tracker has
      // been consumed by network input DStreams, and jobs have been generated with them
      logInfo("Waiting for all received blocks to be consumed for job generation")
      while(!hasTimedOut && jobScheduler.receiverTracker.hasUnallocatedBlocks) {
        Thread.sleep(pollTime)
      }
      logInfo("Waited for all received blocks to be consumed for job generation")

      // Stop generating jobs
      val stopTime = timer.stop(interruptTimer = false)
      graph.stop()
      logInfo("Stopped generation timer")

      // Wait for the jobs to complete and checkpoints to be written
      def haveAllBatchesBeenProcessed: Boolean = {
        lastProcessedBatch != null && lastProcessedBatch.milliseconds == stopTime
      }
      logInfo("Waiting for jobs to be processed and checkpoints to be written")
      while (!hasTimedOut && !haveAllBatchesBeenProcessed) {
        Thread.sleep(pollTime)
      }
      logInfo("Waited for jobs to be processed and checkpoints to be written")
    } else {
      logInfo("Stopping JobGenerator immediately")
      // Stop timer and graph immediately, ignore unprocessed data and pending jobs
      timer.stop(true)
      graph.stop()
    }

    // Stop the event loop and checkpoint writer
    if (shouldCheckpoint) checkpointWriter.stop()
    eventLoop.stop()
    logInfo("Stopped JobGenerator")
  }

  /**
   * Callback called when a batch has been completely processed.
   */
  def onBatchCompletion(time: Time) {
    eventLoop.post(ClearMetadata(time))
  }

  /**
   * Callback called when the checkpoint of a batch has been written.
   */
  def onCheckpointCompletion(time: Time, clearCheckpointDataLater: Boolean) {
    if (clearCheckpointDataLater) {
      eventLoop.post(ClearCheckpointData(time))
    }
  }

  /** Processes all events */
  private def processEvent(event: JobGeneratorEvent) {
    logDebug("Got event " + event)
    event match {
      case GenerateJobs(time) => generateJobs(time)
      case ClearMetadata(time) => clearMetadata(time)
      case DoCheckpoint(time, clearCheckpointDataLater) =>
        doCheckpoint(time, clearCheckpointDataLater)
      case ClearCheckpointData(time) => clearCheckpointData(time)
    }
  }

  /** Starts the generator for the first time */
  private def startFirstTime() {
    val startTime = new Time(timer.getStartTime())
    graph.start(startTime - graph.batchDuration)


    // timer为RecurringTimer，
    // 而RecurringTimer中启动了一个线程，该线程的run方法调用了RecurringTimer中的loop方法，
    // 而loop方法循环调用callback方法，
    // 而callback方法在初始化timer时已经指定，
    // longTime => eventLoop.post(GenerateJobs(new Time(longTime))) is callback

    /** see line 88
     * 每隔一段时间RecurringTimer会生成一个JobGeneratorEvent(既GenerateJobs)，
     * 并将此事件JobGeneratorEvent放入到EventLoop类中
     * 维护的eventQueue（既java.util.concurrent.LinkedBlockingDeque）队列中,
     * EventLoop会启动一个线程，当有事件放入此队列时，
     * 执行回调函数onReceive，也就是JobGenerator中的processEvent，
     * 进而执行generateJobs方法，进而调用graph.generateJobs(time),
     * generateJobs方法会生成Seq[Job]既JobSet，该JobSet中包含完整的blocks数据块和RDD DAG实例（运算的逻辑）
     * generateJobs方法中生成JobSet成功后，会调用jobScheduler.submitJobSet(JobSet(time, jobs, streamIdToInputInfos))
     */

    timer.start(startTime.milliseconds)
    logInfo("Started JobGenerator at " + startTime)
  }

  /** Restarts the generator based on the information in checkpoint */
  private def restart() {
    // If manual clock is being used for testing, then
    // either set the manual clock to the last checkpointed time,
    // or if the property is defined set it to that time
    if (clock.isInstanceOf[ManualClock]) {
      val lastTime = ssc.initialCheckpoint.checkpointTime.milliseconds
      val jumpTime = ssc.sc.conf.getLong("spark.streaming.manualClock.jump", 0)
      clock.asInstanceOf[ManualClock].setTime(lastTime + jumpTime)
    }

    val batchDuration = ssc.graph.batchDuration

    // Batches when the master was down, that is,
    // between the checkpoint and current restart time
    val checkpointTime = ssc.initialCheckpoint.checkpointTime
    val restartTime = new Time(timer.getRestartTime(graph.zeroTime.milliseconds))
    val downTimes = checkpointTime.until(restartTime, batchDuration)
    logInfo("Batches during down time (" + downTimes.size + " batches): "
      + downTimes.mkString(", "))

    // Batches that were unprocessed before failure
    val pendingTimes = ssc.initialCheckpoint.pendingTimes.sorted(Time.ordering)
    logInfo("Batches pending processing (" + pendingTimes.size + " batches): " +
      pendingTimes.mkString(", "))
    // Reschedule jobs for these times
    val timesToReschedule = (pendingTimes ++ downTimes).filter { _ < restartTime }
      .distinct.sorted(Time.ordering)
    logInfo("Batches to reschedule (" + timesToReschedule.size + " batches): " +
      timesToReschedule.mkString(", "))
    timesToReschedule.foreach { time =>
      // Allocate the related blocks when recovering from failure, because some blocks that were
      // added but not allocated, are dangling in the queue after recovering, we have to allocate
      // those blocks to the next batch, which is the batch they were supposed to go.
      jobScheduler.receiverTracker.allocateBlocksToBatch(time) // allocate received blocks to batch
      jobScheduler.submitJobSet(JobSet(time, graph.generateJobs(time)))
    }

    // Restart the timer
    timer.start(restartTime.milliseconds)
    logInfo("Restarted JobGenerator at " + restartTime)
  }

  /** Generate jobs and perform checkpoint for the given `time`.  */
  private def generateJobs(time: Time) {
    // Set the SparkEnv in this thread, so that job generation code can access the environment
    // Example: BlockRDDs are created in this thread, and it needs to access BlockManager
    // Update: This is probably redundant after threadlocal stuff in SparkEnv has been removed.
    SparkEnv.set(ssc.env)
    Try {
      // 第1步: ReceiverTracker 将目前已收到的数据进行一次allocate，即将上次batch切分后的数据切分到到本次新的batch里
      jobScheduler.receiverTracker.allocateBlocksToBatch(time) // allocate received blocks to batch

      // 第2步: 生成 本batch的RDD DAG 实例
      // 要求DStreamGraph复制出一套新的RDD DAG的实例，
      // 具体过程是：DStreamGraph将要求图里的尾DStream节点生成具体的RDD实例，
      // 并递归的调用尾 DStream 的上游DStream节点……以此遍历整个 DStreamGraph，遍历结束也就正好生成了RDD DAG的实例
      graph.generateJobs(time) // generate jobs using allocated block
    } match {
      case Success(jobs) =>
        // 第3步: 获取第1步ReceiverTracker分配到本batch的源头数据的meta信息
        // streamIdToInputInfos:Map[Int, StreamInputInfo]
        val streamIdToInputInfos = jobScheduler.inputInfoTracker.getInfo(time)


        // 第4步: 将第2步生成的本batch的RDD DAG，和第3步获取到的meta信息，一同提交给JobScheduler异步执行
        // 这里我们提交的是将(a)time (b)Seq[job] (c)块数据的meta信息 这三者包装为一个JobSet，然后调用 JobScheduler.submitJobSet(JobSet) 提交给 JobScheduler
        // 这里的向 JobScheduler 提交过程与 JobScheduler 接下来在 jobExecutor 里执行过程是异步分离的，因此本步将非常快即可返回
        jobScheduler.submitJobSet(JobSet(time, jobs, streamIdToInputInfos))

      case Failure(e) =>
        jobScheduler.reportError("Error generating jobs for time " + time, e)
    }
    // 第5步: 只要提交结束（不管是否已开始异步执行），就马上对整个系统的当前运行状态做一个checkpoint
    // 这里做checkpoint也只是异步提交一个DoCheckpoint消息请求，不用等checkpoint 真正写完成 即可返回
    // 这里也简单描述一下checkpoint包含的内容，包括已经提交了的、但尚未运行结束的JobSet等实际运行时信息
    eventLoop.post(DoCheckpoint(time, clearCheckpointDataLater = false))
  }

  /** Clear DStream metadata for the given `time`. */
  private def clearMetadata(time: Time) {
    ssc.graph.clearMetadata(time)

    // If checkpointing is enabled, then checkpoint,
    // else mark batch to be fully processed
    if (shouldCheckpoint) {
      eventLoop.post(DoCheckpoint(time, clearCheckpointDataLater = true))
    } else {
      // If checkpointing is not enabled, then delete metadata information about
      // received blocks (block data not saved in any case). Otherwise, wait for
      // checkpointing of this batch to complete.
      val maxRememberDuration = graph.getMaxInputStreamRememberDuration()
      jobScheduler.receiverTracker.cleanupOldBlocksAndBatches(time - maxRememberDuration)
      jobScheduler.inputInfoTracker.cleanup(time - maxRememberDuration)
      markBatchFullyProcessed(time)
    }
  }

  /** Clear DStream checkpoint data for the given `time`. */
  private def clearCheckpointData(time: Time) {
    ssc.graph.clearCheckpointData(time)

    // All the checkpoint information about which batches have been processed, etc have
    // been saved to checkpoints, so its safe to delete block metadata and data WAL files
    val maxRememberDuration = graph.getMaxInputStreamRememberDuration()
    jobScheduler.receiverTracker.cleanupOldBlocksAndBatches(time - maxRememberDuration)
    jobScheduler.inputInfoTracker.cleanup(time - maxRememberDuration)
    markBatchFullyProcessed(time)
  }

  /** Perform checkpoint for the give `time`. */
  private def doCheckpoint(time: Time, clearCheckpointDataLater: Boolean) {
    if (shouldCheckpoint && (time - graph.zeroTime).isMultipleOf(ssc.checkpointDuration)) {
      logInfo("Checkpointing graph for time " + time)
      ssc.graph.updateCheckpointData(time)
      checkpointWriter.write(new Checkpoint(ssc, time), clearCheckpointDataLater)
    }
  }

  private def markBatchFullyProcessed(time: Time) {
    lastProcessedBatch = time
  }
}
