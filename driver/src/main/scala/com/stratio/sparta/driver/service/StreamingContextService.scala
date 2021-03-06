/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparta.driver.service

import java.io.File

import akka.actor.ActorRef
import akka.event.slf4j.SLF4JLogging
import akka.pattern.ask
import akka.util.Timeout
import com.stratio.sparta.driver.SpartaJob
import com.stratio.sparta.driver.SpartaJob._
import com.stratio.sparta.driver.factory._
import com.stratio.sparta.sdk._
import com.stratio.sparta.serving.core.constants.AppConstant
import com.stratio.sparta.serving.core.models._
import com.stratio.sparta.serving.core.policy.status.PolicyStatusActor.{AddListener, Update}
import com.stratio.sparta.serving.core.policy.status.PolicyStatusEnum._
import com.typesafe.config.Config
import org.apache.curator.framework.recipes.cache.NodeCache
import org.apache.spark.SparkContext
import org.apache.spark.streaming.StreamingContext

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Success, Try}

case class StreamingContextService(policyStatusActor: Option[ActorRef] = None, generalConfig: Option[Config] = None)
  extends SLF4JLogging {

  implicit val timeout: Timeout = Timeout(4.seconds)
  final val OutputsSparkConfiguration = "getSparkConfiguration"

  def standAloneStreamingContext(apConfig: AggregationPoliciesModel, files: Seq[File]): Option[StreamingContext] = {
    runStatusListener(apConfig.id.get, apConfig.name)

    val ssc = StreamingContext.getOrCreate(generateCheckpointPath(apConfig), () => {
      log.info(s"Nothing in checkpoint path: ${generateCheckpointPath(apConfig)}")
      SpartaJob(apConfig).run(getStandAloneSparkContext(apConfig, files))
    })

    SparkContextFactory.setSparkContext(ssc.sparkContext)
    SparkContextFactory.setSparkStreamingContext(ssc)
    SparkContextFactory.setInitialSentences(apConfig.initSqlSentences.map(modelSentence => modelSentence.sentence))

    Option(ssc)
  }

  def clusterStreamingContext(apConfig: AggregationPoliciesModel,
                              files: Seq[String],
                              detailConfig: Map[String, String]): Option[StreamingContext] = {
    val exitWhenStop = true

    runStatusListener(apConfig.id.get, apConfig.name, exitWhenStop)

    val ssc = StreamingContext.getOrCreate(generateCheckpointPath(apConfig), () => {
      log.info(s"Nothing in checkpoint path: ${generateCheckpointPath(apConfig)}")
      SpartaJob(apConfig).run(getClusterSparkContext(apConfig, files, detailConfig))
    })

    SparkContextFactory.setSparkContext(ssc.sparkContext)
    SparkContextFactory.setSparkStreamingContext(ssc)
    SparkContextFactory.setInitialSentences(apConfig.initSqlSentences.map(modelSentence => modelSentence.sentence))

    Option(ssc)
  }

  private def getStandAloneSparkContext(apConfig: AggregationPoliciesModel, jars: Seq[File]): SparkContext = {
    val pluginsSparkConfig = SpartaJob.getSparkConfigs(apConfig, OutputsSparkConfiguration, Output.ClassSuffix)
    val policySparkConfig = SpartaJob.getSparkConfigFromPolicy(apConfig)
    val standAloneConfig = Try(generalConfig.get.getConfig(AppConstant.ConfigLocal)) match {
      case Success(config) => Some(config)
      case _ => None
    }

    SparkContextFactory.sparkStandAloneContextInstance(standAloneConfig, policySparkConfig ++ pluginsSparkConfig, jars)
  }

  private def getClusterSparkContext(apConfig: AggregationPoliciesModel,
                                     classPath: Seq[String],
                                     detailConfig: Map[String, String]): SparkContext = {
    val pluginsSparkConfig = SpartaJob.getSparkConfigs(apConfig, OutputsSparkConfiguration, Output.ClassSuffix)
    val policySparkConfig = SpartaJob.getSparkConfigFromPolicy(apConfig)

    SparkContextFactory.sparkClusterContextInstance(policySparkConfig ++ pluginsSparkConfig ++ detailConfig, classPath)
  }

  private def runStatusListener(policyId: String, name: String, exit: Boolean = false): Unit = {
    if (policyStatusActor.isDefined) {
      log.info(s"Listener added for: $policyId")
      policyStatusActor.get ? AddListener(policyId, (policyStatus: PolicyStatusModel, nodeCache: NodeCache) => {
        synchronized {
          if (policyStatus.status.id equals Stopping.id) {
            try {
              log.info("Stopping message received from Zookeeper")
              SparkContextFactory.destroySparkStreamingContext()
            } finally {
              try {
                Await.result(policyStatusActor.get ? Update(PolicyStatusModel(policyId, Stopped)), timeout.duration)
                match {
                  case None => log.warn(s"The policy status can not be changed")
                  case Some(_) => log.debug(s"The policy status is changed to Stopped in finish action")
                }
              } catch {
                case e: Exception =>
                  log.warn(s"The policy status could not be changed correctly. Exception: ${e.getLocalizedMessage}")
              }
              try {
                nodeCache.close()
              } catch {
                case e: Exception =>
                  log.warn(s"The nodeCache in Zookeeper is not closed correctly.  Exception: ${e.getLocalizedMessage}")
              }
              if (exit) {
                SparkContextFactory.destroySparkContext()
                log.info("Closing the application")
                System.exit(0)
              }
            }
          }
        }
      })
    }
  }
}