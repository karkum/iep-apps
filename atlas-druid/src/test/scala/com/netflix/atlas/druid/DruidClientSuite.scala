/*
 * Copyright 2014-2022 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.druid

import java.io.IOException
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.scala.JavaTypeable
import com.netflix.atlas.akka.AccessLogger
import com.netflix.atlas.json.Json
import com.typesafe.config.ConfigFactory
import munit.FunSuite

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

class DruidClientSuite extends FunSuite {

  import DruidClient._

  private val config = ConfigFactory.load().getConfig("atlas.druid")

  private implicit val system: ActorSystem = ActorSystem(getClass.getSimpleName)

  private def newClient(result: Try[HttpResponse]): DruidClient = {
    val client = Flow[(HttpRequest, AccessLogger)]
      .map {
        case (_, logger) => result -> logger
      }
    new DruidClient(config, system, client)
  }

  private def ok[T: JavaTypeable](data: T): HttpResponse = {
    val json = Json.encode(data).getBytes(StandardCharsets.UTF_8)
    HttpResponse(StatusCodes.OK, entity = json)
  }

  override def afterAll(): Unit = {
    Await.result(system.terminate(), Duration.Inf)
    super.afterAll()
  }

  test("get datasources") {
    val client = newClient(Success(ok(List("a", "b", "c"))))
    val future = client.datasources.runWith(Sink.head)
    val result = Await.result(future, Duration.Inf)
    assertEquals(result, List("a", "b", "c"))
  }

  test("get datasources http error") {
    intercept[IOException] {
      val client = newClient(Success(HttpResponse(StatusCodes.BadRequest)))
      val future = client.datasources.runWith(Sink.head)
      Await.result(future, Duration.Inf)
    }
  }

  test("get datasources connect timeout") {
    intercept[ConnectException] {
      val client = newClient(Failure(new ConnectException("failed")))
      val future = client.datasources.runWith(Sink.head)
      Await.result(future, Duration.Inf)
    }
  }

  test("get datasources read failure") {
    intercept[IOException] {
      val data = Source.failed[ByteString](new IOException("read failed"))
      val entity = HttpEntity(MediaTypes.`application/json`, data)
      val response = HttpResponse(StatusCodes.OK, entity = entity)
      val client = newClient(Success(response))
      val future = client.datasources.runWith(Sink.head)
      Await.result(future, Duration.Inf)
    }
  }

  test("get datasources bad json output") {
    intercept[JsonMappingException] {
      val json = """{"foo":"bar"}"""
      val data = Source.single[ByteString](ByteString(json))
      val entity = HttpEntity(MediaTypes.`application/json`, data)
      val response = HttpResponse(StatusCodes.OK, entity = entity)
      val client = newClient(Success(response))
      val future = client.datasources.runWith(Sink.head)
      Await.result(future, Duration.Inf)
    }
  }

  test("get datasource empty") {
    val client = newClient(Success(ok(Datasource(Nil, Nil))))
    val future = client.datasource("abc").runWith(Sink.head)
    val result = Await.result(future, Duration.Inf)
    assertEquals(result, Datasource(Nil, Nil))
  }

  test("get datasource with data") {
    val ds = Datasource(List("a", "b"), List(Metric("m1", "LONG"), Metric("m2", "LONG")))
    val client = newClient(Success(ok(ds)))
    val future = client.datasource("abc").runWith(Sink.head)
    val result = Await.result(future, Duration.Inf)
    assertEquals(result, ds)
  }

  private def executeSegmentMetadataRequest: List[SegmentMetadataResult] = {
    import com.netflix.atlas.core.util.Streams._
    val file = "segmentMetadataResponse.json"
    val payload = Using.resource(resource(file))(byteArray)
    val response = HttpResponse(StatusCodes.OK, entity = payload)
    val client = newClient(Success(response))
    val query = SegmentMetadataQuery("test")
    val future = client.segmentMetadata(query).runWith(Sink.head)
    Await.result(future, Duration.Inf)
  }

  test("segmentMetadata columns") {
    val result = executeSegmentMetadataRequest
    assertEquals(result.size, 1)

    val columns = result.head.columns

    val expected = Set(
      "__time",
      "test.metric.counter",
      "test.dim.1",
      "test.dim.2",
      "test.metric.histogram.dist.1",
      "test.metric.histogram.dist.2",
      "test.metric.histogram.timer"
    )
    assertEquals(columns.keySet, expected)
  }

  test("segmentMetadata column types") {
    val columns = executeSegmentMetadataRequest.head.columns
    assertEquals(columns("__time").`type`, "LONG")
    assertEquals(columns("test.metric.counter").`type`, "LONG")
    assertEquals(columns("test.metric.histogram.dist.1").`type`, "spectatorHistogram")
    assertEquals(columns("test.metric.histogram.dist.2").`type`, "spectatorHistogramDistribution")
    assertEquals(columns("test.metric.histogram.timer").`type`, "spectatorHistogramTimer")
    assertEquals(columns("test.dim.1").`type`, "STRING")
    assertEquals(columns("test.dim.1").`type`, "STRING")
  }

  test("segmentMetadata metrics") {
    val ds = executeSegmentMetadataRequest.head.toDatasource
    ds.metrics.foreach { m =>
      m.name match {
        case "test.metric.counter"          => assert(m.isCounter)
        case "test.metric.histogram.dist.1" => assert(m.isDistSummary)
        case "test.metric.histogram.dist.2" => assert(m.isDistSummary)
        case "test.metric.histogram.timer"  => assert(m.isTimer)
        case name                           => throw new MatchError(name)
      }
    }
  }

  test("segmentMetadata aggregators") {
    val aggregators = executeSegmentMetadataRequest.head.aggregators
    assertEquals(aggregators.size, 2)

    val expected = Set(
      "test.metric.counter",
      "test.metric.histogram"
    )
    assertEquals(aggregators.keySet, expected)
  }

  private def executeGroupByRequest: List[GroupByDatapoint] = {
    import com.netflix.atlas.core.util.Streams._
    val file = "groupByResponseArray.json"
    val payload = Using.resource(resource(file))(byteArray)
    val response = HttpResponse(StatusCodes.OK, entity = payload)
    val client = newClient(Success(response))
    val query =
      GroupByQuery("test", List(DefaultDimensionSpec("percentile", "percentile")), Nil, Nil)
    val future = client.groupBy(query).runWith(Sink.head)
    Await.result(future, Duration.Inf)
  }

  test("groupBy filter out null dimensions") {
    val datapoints = executeGroupByRequest
    assertEquals(datapoints.count(_.tags.isEmpty), 2)
    assertEquals(datapoints.count(_.tags.nonEmpty), 5)
  }

  private def executeGroupByHistogramRequest: List[GroupByDatapoint] = {
    import com.netflix.atlas.core.util.Streams._
    val file = "groupByResponseHisto.json"
    val payload = Using.resource(resource(file))(byteArray)
    val response = HttpResponse(StatusCodes.OK, entity = payload)
    val client = newClient(Success(response))
    val query =
      GroupByQuery("test", Nil, Nil, List(Aggregation.timer("value")))
    val future = client.groupBy(query).runWith(Sink.head)
    Await.result(future, Duration.Inf)
  }

  test("groupBy with histogram aggregation type") {
    val expected = executeGroupByRequest.filter(_.tags.nonEmpty)
    val actual = executeGroupByHistogramRequest
    assertEquals(actual, expected)
  }

  test("aggregation encode, timer type") {
    val aggr = Aggregation.timer("foo")
    val json = Json.encode(aggr)
    assert(json.contains("spectatorHistogram"))
    assert(!json.contains("aggrType"))
  }

  test("aggregation encode, dist summary type") {
    val aggr = Aggregation.distSummary("foo")
    val json = Json.encode(aggr)
    assert(json.contains("spectatorHistogram"))
    assert(!json.contains("aggrType"))
  }

  test("aggregation encode, doubleSum type") {
    val aggr = Aggregation.sum("foo")
    val json = Json.encode(aggr)
    assert(json.contains("doubleSum"))
    assert(!json.contains("aggrType"))
  }
}
