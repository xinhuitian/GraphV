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
package org.apache.spark.graphxpp.impl

import org.apache.spark.graphxpp.{EdgeTriplet, VertexId}

/**
 * Created by XinhuiTian on 17/3/27.
 */
abstract class ExecutionContext[VD, ED, A] {
  /** The vertex id of the edge's source vertex. */
  def srcId: VertexId
  /** The vertex id of the edge's destination vertex. */
  def dstId: VertexId
  /** The vertex attribute of the edge's source vertex. */
  def srcAttr: VD
  /** The vertex attribute of the edge's destination vertex. */
  def dstAttr: VD
  /** The attribute associated with the edge. */
  def attr: ED

  /** Sends a message to the source vertex. */
  def sendToSrc(msg: A): Unit
  /** Sends a message to the destination vertex. */
  def sendToDst(msg: A): Unit

  def toEdgeTriplet: EdgeTriplet[ED, VD] = {
    val et = new EdgeTriplet[ED, VD]
    et.srcId = srcId
    et.srcAttr = srcAttr
    et.dstId = dstId
    et.dstAttr = dstAttr
    et.attr = attr
    et
  }
}
