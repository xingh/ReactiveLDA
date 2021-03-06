package com.kifi.lda

import akka.actor._
import scala.io.Source
import akka.routing.RoundRobinRouter
import scala.collection.mutable
import scala.math._
import scala.util.Random
import org.apache.commons.math3.random.Well19937c

class BetaActor(batchReader: ActorRef, config: LDAConfig) extends Actor with Logging{

  val workerRouter = context.actorOf(Props(classOf[DocSamplingActor], config.numTopics, config.alpha).withRouter(RoundRobinRouter(config.nworker)), name = "workerRouter")
  val thetas = mutable.Map.empty[Int, Array[Float]]
  val beta: Beta = Beta(new Array[Float](config.numTopics * config.vocSize), config.numTopics, config.vocSize)
  val burnedBeta: Beta = Beta(new Array[Float](config.numTopics * config.vocSize), config.numTopics, config.vocSize) 
  val wordTopicCounts: WordTopicCounts = WordTopicCounts(new Array[Int](config.numTopics * config.vocSize), config.numTopics, config.vocSize)
  val miniBatchSize = config.miniBatchSize
  val burnIn = config.burnIn
  val skip = config.skip
  var betaUpdatedTimes = 0
  var numBetaSamples = 0
  val eta = config.eta
  val tracker = BatchProgressTracker(config.iterations)
  val rng = new Well19937c()
  private val dirSampler = new FastDirichletSampler(rng)
  
  log.setLevel(config.loglevel)
  
  def receive = {
    case StartTraining => batchReader ! NextMiniBatchRequest
    case MiniBatchDocs(docs, wholeBatchEnded) => {
      if (wholeBatchEnded) tracker.isLastMiniBatch = true
      else tracker.isLastMiniBatch = false

      tracker.startTrackingMiniBatch(docs.size)
      dispatchJobs(docs)
    }
    case result: SamplingResult => handleSamplingResult(result)
  }

  private def dispatchJobs(docs: Seq[Doc]) = {
    if (tracker.initialUniformSampling) docs.foreach{ doc => workerRouter ! UniformSampling(doc) }
    else docs.foreach{ doc => workerRouter ! Sampling(doc, Theta(thetas(doc.index)), beta) }
  }
      
  private def updateBeta(): Unit = {
    log.info(self.path.name + ": updating beta")
    
    (0 until config.numTopics).foreach { t =>
      val counts = wordTopicCounts.getRow(t).map{_ + eta}
      log.debug(s"sampling dirichlet with ${counts.take(10).mkString(" ")}")
      val b = dirSampler.sample(counts)
      log.debug(s"sampled beta for topic $t: ${b.take(10).mkString(" ")}")
      beta.setRow(t, b)
    }
    
    log.info(self.path.name + ": beta updated")
    
    updateBurnInBeta()
  }
  
  private def updateBurnInBeta(): Unit = {
    betaUpdatedTimes += 1
    if (betaUpdatedTimes >= burnIn && ((betaUpdatedTimes - burnIn) % skip == 0)){
      log.debug("updating burn-in beta")
      numBetaSamples += 1
      var i = 0
      val n = burnedBeta.value.size
      while (i < n) { burnedBeta.value(i) += beta.value(i); i += 1} 
    }
  }
  
  private def saveModel(): Unit = {
    println("saving model...")
    avgBeta()
    Beta.toFile(burnedBeta, config.saveBetaPath)
  }
  
  private def avgBeta(): Unit = {
    var i = 0
    val n = burnedBeta.value.size
    while (i < n) { burnedBeta.value(i) /= numBetaSamples; i += 1} 
  }

  private def handleSamplingResult(result: SamplingResult){
    tracker.increMiniBatchCounter()
    updateWordTopicCounts(result)
    thetas(result.docIndex) = result.theta.value

    (tracker.miniBatchFinished, tracker.isLastMiniBatch) match {
      case (false, _) =>
      case (true, false) => batchReader ! NextMiniBatchRequest
      case (true, true) => {
        tracker.increBatchCounter()
        updateBeta()
        if (tracker.batchFinished) {
          saveModel()
          context.system.shutdown()
        }  else {
          wordTopicCounts.clearAll()
          batchReader ! NextMiniBatchRequest
        }
      }
    }
  }

  private def updateWordTopicCounts(result: SamplingResult){
    result.wordTopicAssign.value.foreach{ case (wordId, topicId) => wordTopicCounts.incre(topicId, wordId) }
  }
  
}
