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

package org.apache.spark.graphxp.impl

import scala.reflect.ClassTag

import org.apache.spark._

import org.apache.spark.graphxp._
import org.apache.spark.rdd._
import org.apache.spark.storage.StorageLevel

class VertexRDDImpl[VD] private[graphxp] (
    @transient val partitionsRDD: RDD[ShippableVertexPartition[VD]],
    val targetStorageLevel: StorageLevel = StorageLevel.MEMORY_ONLY)
  (implicit override protected val vdTag: ClassTag[VD])
  extends VertexRDD[VD](partitionsRDD.context, List(new OneToOneDependency(partitionsRDD))) {

  require(partitionsRDD.partitioner.isDefined)

  // override def reindex(): graphxp.VertexRDD[VD] = this.withPartitionsRDD(partitionsRDD.map(_.reindex()))

  override val partitioner = partitionsRDD.partitioner

  override protected def getPreferredLocations(s: Partition): Seq[String] = {
    println("Get Prefer location in VertexRDDImpl")
    val locs = partitionsRDD.preferredLocations (s)
    println(locs)
    locs
  }

  override def setName(_name: String): this.type = {
    if (partitionsRDD.name != null) {
      partitionsRDD.setName(partitionsRDD.name + ", " + _name)
    } else {
      partitionsRDD.setName(_name)
    }
    this
  }
  setName("VertexRDD")

  /**
   * Persists the vertex partitions at the specified storage level, ignoring any existing target
   * storage level.
   */
  override def persist(newLevel: StorageLevel): this.type = {
    partitionsRDD.persist(newLevel)
    this
  }

  override def unpersist(blocking: Boolean = true): this.type = {
    partitionsRDD.unpersist(blocking)
    this
  }

  /** Persists the vertex partitions at `targetStorageLevel`, which defaults to MEMORY_ONLY. */
  override def cache(): this.type = {
    partitionsRDD.persist(targetStorageLevel)
    this
  }

  override def getStorageLevel: StorageLevel = partitionsRDD.getStorageLevel

  override def checkpoint(): Unit = {
    partitionsRDD.checkpoint()
  }

  override def isCheckpointed: Boolean = {
    firstParent[ShippableVertexPartition[VD]].isCheckpointed
  }

  override def getCheckpointFile: Option[String] = {
    partitionsRDD.getCheckpointFile
  }

  /** The number of vertices in the RDD. */
  override def count(): Long = {
    partitionsRDD.map(_.size.toLong).reduce(_ + _)
  }

  override private[graphxp] def mapVertexPartitions[VD2: ClassTag](
      f: ShippableVertexPartition[VD] => ShippableVertexPartition[VD2])
    : graphxp.VertexRDD[VD2] = {
    val newPartitionsRDD = partitionsRDD.mapPartitions(_.map(f), preservesPartitioning = true)
    this.withPartitionsRDD(newPartitionsRDD)
  }

  override def mapValues[VD2: ClassTag](f: VD => VD2): graphxp.VertexRDD[VD2] =
    this.mapVertexPartitions(_.map((vid, attr) => f(attr)))

  override def mapValues[VD2: ClassTag](f: (VertexId, VD) => VD2): graphxp.VertexRDD[VD2] =
    this.mapVertexPartitions(_.map(f))

  override def minus(other: RDD[(VertexId, VD)]): graphxp.VertexRDD[VD] = {
    minus(this.aggregateUsingIndex(other, (a: VD, b: VD) => a))
  }

  override def minus (other: graphxp.VertexRDD[VD]): graphxp.VertexRDD[VD] = {
    other match {
      case other: graphxp.VertexRDD[_] if this.partitioner == other.partitioner =>
        this.withPartitionsRDD[VD](
          partitionsRDD.zipPartitions(
            other.partitionsRDD, preservesPartitioning = true) {
            (thisIter, otherIter) =>
              val thisPart = thisIter.next()
              val otherPart = otherIter.next()
              Iterator(thisPart.minus(otherPart))
          })
      case _ =>
        this.withPartitionsRDD[VD](
          partitionsRDD.zipPartitions(
            other.partitionBy(this.partitioner.get), preservesPartitioning = true) {
            (partIter, msgs) => partIter.map(_.minus(msgs))
          }
        )
    }
  }

  override def diff(other: RDD[(VertexId, VD)]): graphxp.VertexRDD[VD] = {
    diff(this.aggregateUsingIndex(other, (a: VD, b: VD) => a))
  }

  override def diff(other: graphxp.VertexRDD[VD]): graphxp.VertexRDD[VD] = {
    val otherPartition = other match {
      case other: graphxp.VertexRDD[_] if this.partitioner == other.partitioner =>
        other.partitionsRDD
      case _ =>
        graphxp.VertexRDD(other.partitionBy(this.partitioner.get)).partitionsRDD
    }
    val newPartitionsRDD = partitionsRDD.zipPartitions(
      otherPartition, preservesPartitioning = true
    ) { (thisIter, otherIter) =>
      val thisPart = thisIter.next()
      val otherPart = otherIter.next()
      Iterator(thisPart.diff(otherPart))
    }
    this.withPartitionsRDD(newPartitionsRDD)
  }

  override def leftZipJoin[VD2: ClassTag, VD3: ClassTag]
      (other: graphxp.VertexRDD[VD2])(f: (VertexId, VD, Option[VD2]) => VD3): graphxp.VertexRDD[VD3] = {
    val st = System.currentTimeMillis()
    val newPartitionsRDD = partitionsRDD.zipPartitions(
      other.partitionsRDD, preservesPartitioning = true
    ) { (thisIter, otherIter) =>
      val thisPart = thisIter.next()
      val otherPart = otherIter.next()
      Iterator(thisPart.leftJoin(otherPart)(f))
    }

    // newPartitionsRDD.count()
    // val et = System.currentTimeMillis()
    // println("leftJoin: " + (et - st))
    this.withPartitionsRDD(newPartitionsRDD)
  }

  override def leftJoin[VD2: ClassTag, VD3: ClassTag]
      (other: RDD[(VertexId, VD2)])
      (f: (VertexId, VD, Option[VD2]) => VD3)
    : graphxp.VertexRDD[VD3] = {
    // Test if the other vertex is a VertexRDD to choose the optimal join strategy.
    // If the other set is a VertexRDD then we use the much more efficient leftZipJoin
    other match {
      case other: graphxp.VertexRDD[_] if this.partitioner == other.partitioner =>
        println("Same partition")
        leftZipJoin(other)(f)
      case _ =>
        this.withPartitionsRDD[VD3](
          partitionsRDD.zipPartitions(
            other.partitionBy(this.partitioner.get), preservesPartitioning = true) {
            (partIter, msgs) => partIter.map(_.leftJoin(msgs)(f))
          }
        )
    }
  }

  override def innerZipJoin[U: ClassTag, VD2: ClassTag](other: graphxp.VertexRDD[U])
      (f: (VertexId, VD, U) => VD2): graphxp.VertexRDD[VD2] = {
    val newPartitionsRDD = partitionsRDD.zipPartitions(
      other.partitionsRDD, preservesPartitioning = true
    ) { (thisIter, otherIter) =>
      val thisPart = thisIter.next()
      val otherPart = otherIter.next()
      Iterator(thisPart.innerJoin(otherPart)(f))
    }
    this.withPartitionsRDD(newPartitionsRDD)
  }

  override def innerJoin[U: ClassTag, VD2: ClassTag](other: RDD[(VertexId, U)])
      (f: (VertexId, VD, U) => VD2): graphxp.VertexRDD[VD2] = {
    // Test if the other vertex is a VertexRDD to choose the optimal join strategy.
    // If the other set is a VertexRDD then we use the much more efficient innerZipJoin
    other match {
      case other: graphxp.VertexRDD[_] if this.partitioner == other.partitioner =>
        innerZipJoin(other)(f)
      case _ =>
        this.withPartitionsRDD(
          partitionsRDD.zipPartitions(
            other.partitionBy(this.partitioner.get), preservesPartitioning = true) {
            (partIter, msgs) => partIter.map(_.innerJoin(msgs)(f))
          }
        )
    }
  }

  override def aggregateUsingIndex[VD2: ClassTag](
      messages: RDD[(VertexId, VD2)], reduceFunc: (VD2, VD2) => VD2): graphxp.VertexRDD[VD2] = {
    val shuffled = messages.partitionBy(this.partitioner.get)
    val parts = partitionsRDD.zipPartitions(shuffled, true) { (thisIter, msgIter) =>
      thisIter.map(_.aggregateUsingIndex(msgIter, reduceFunc))
    }
    this.withPartitionsRDD[VD2](parts)
  }

  /*
  override def reverseRoutingTables(): graphxp.VertexRDD[VD] =
    this.mapVertexPartitions(vPart => vPart.withRoutingTable(vPart.routingTable.reverse))
    */

  override def withEdges(edges: EdgeRDD[_]): VertexRDD[VD] = {
    val routingTables = graphxp.VertexRDD.createLocalRoutingTables(edges, this.partitioner.get)
    val vertexPartitions = partitionsRDD.zipPartitions(routingTables, true) {
      (partIter, routingTableIter) =>
        val routingTable =
          if (routingTableIter.hasNext) routingTableIter.next() else LocalRoutingTablePartition.empty
        partIter.map(_.withRoutingTable(routingTable))
    }
    this.withPartitionsRDD(vertexPartitions)
  }

  override private[graphxp] def withPartitionsRDD[VD2: ClassTag](
      partitionsRDD: RDD[ShippableVertexPartition[VD2]]): graphxp.VertexRDD[VD2] = {
    new VertexRDDImpl(partitionsRDD, this.targetStorageLevel)
  }

  override private[graphxp] def withTargetStorageLevel(
      targetStorageLevel: StorageLevel): graphxp.VertexRDD[VD] = {
    new VertexRDDImpl(this.partitionsRDD, targetStorageLevel)
  }

  override private[graphxp] def shipVertexAttributes(
      shipSrc: Boolean, shipDst: Boolean): RDD[(PartitionID, VertexAttributeBlock[VD])] = {
    partitionsRDD.mapPartitions(_.flatMap(_.shipVertexAttributes(shipSrc, shipDst)))
  }

  override private[graphxp] def shipVertexIds(): RDD[(PartitionID, Array[Int])] = {
    partitionsRDD.mapPartitions(_.flatMap(_.shipVertexIds()))
  }

}
