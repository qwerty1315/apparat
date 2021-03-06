/*
 * This file is part of Apparat.
 *
 * Copyright (C) 2010 Joa Ebert
 * http://www.joa-ebert.com/
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package apparat.graph.immutable

import apparat.bytecode.{Marker, Bytecode}
import apparat.bytecode.operations._
import apparat.graph._
import annotation.tailrec

object BytecodeControlFlowGraphBuilder extends (Bytecode => BytecodeControlFlowGraph[ImmutableAbstractOpBlockVertex]) {
	def apply(bytecode: Bytecode) = {
		import collection.mutable.Queue

		type V = ImmutableAbstractOpBlockVertex

		val ops = bytecode.ops
		val markers = bytecode.markers
		val exceptions = bytecode.exceptions

		val blockQueue = new Queue[(V, AbstractOp)]()

		// use to find a target marker
		var vertexMap: Map[V, List[AbstractOp]] = Map.empty

		// use to build the graph
		var edgeMap: Map[V, List[Edge[V]]] = Map.empty

		AbstractOpBasicBlockSlicer(bytecode).foreach {
			opList => {
				var newOpList = opList

				val lastOp = newOpList.last

				// remove label from block
				newOpList.headOption match {
					case Some(op) => if (op.isInstanceOf[Label]) newOpList = newOpList.tail
					case _ =>
				}

				//remove jum from block
				newOpList.lastOption match {
					case Some(op) => if (op.isInstanceOf[Jump]) newOpList = newOpList dropRight 1
					case _ =>
				}

				val vertex = new V(newOpList)

				vertexMap = vertexMap updated (vertex, opList)

				edgeMap = edgeMap updated (vertex, Nil)

				blockQueue += ((vertex, lastOp))
			}
		}

		val entryVertex = new V() {override def toString = "[Entry]"}
		edgeMap = edgeMap updated (entryVertex, Nil)

		val exitVertex = new V() {override def toString = "[Exit]"}
		edgeMap = edgeMap updated (exitVertex, Nil)

		// connect the first block to the entry
		edgeMap = edgeMap updated (entryVertex, JumpEdge(entryVertex, blockQueue.head._1) :: edgeMap(entryVertex))

		def createVertexFromMarker[E <: Edge[V]](startBlock: V, marker: Marker, edgeFactory: (V, V) => E) {
			marker.op map {
				op => vertexMap.view.find(v_op => ((v_op._2 contains op) || (v_op._1 contains op))) match {
					case Some((vertexBlock, ops)) => edgeMap = edgeMap updated (startBlock, edgeFactory(startBlock, vertexBlock) :: edgeMap(startBlock))
					case _ => error("op not found into graph : " + op + "=>" + vertexMap.view.mkString("\n"))
				}
			}
		}

		@tailrec def buildEdge() {
			if (blockQueue.nonEmpty) {
				val (currentBlock, lastOp) = blockQueue.dequeue()

				// check the kind of the last instruction of block
				lastOp match {
					case condOp: AbstractConditionalOp => {
						// the next block into the queue is a false edge
						if (blockQueue.nonEmpty)
							edgeMap = edgeMap updated (currentBlock, FalseEdge(currentBlock, blockQueue.head._1) :: edgeMap(currentBlock))

						// the marker is a TrueEdge
						createVertexFromMarker(currentBlock, condOp.marker, TrueEdge[V] _)
					}

					case jumpOp: Jump => createVertexFromMarker(currentBlock, jumpOp.marker, JumpEdge[V] _)

					case throwOp: Throw => edgeMap = edgeMap updated (currentBlock, ThrowEdge(currentBlock, exitVertex) :: edgeMap(currentBlock))

					case lookupOp: LookupSwitch => {
						createVertexFromMarker(currentBlock, lookupOp.defaultCase, DefaultCaseEdge[V] _)
						lookupOp.cases.zipWithIndex.foreach({
							case (marker, index) => {
								def factory(a: V, b: V) = NumberedCaseEdge[V](a, b, index)
								createVertexFromMarker(currentBlock, marker, factory)
							}
						})
					}
					case returnOp: OpThatReturns => {
						edgeMap = edgeMap updated (currentBlock, ReturnEdge(currentBlock, exitVertex) :: edgeMap(currentBlock))
					}
					case _ => {
						// by default the next block is a jump edge
						// if it s not the last block of the queue
						if (blockQueue.nonEmpty)
							edgeMap = edgeMap updated (currentBlock, JumpEdge(currentBlock, blockQueue.head._1) :: edgeMap(currentBlock))
					}
				}
				// check if it exists a try catch for this block
				if (!currentBlock.isEmpty) {
					val startOpIndex = ops indexOf (currentBlock.head)
					val endOpIndex = ops indexOf (currentBlock.last)
					exceptions.filter(exc => {
						startOpIndex >= ops.indexOf(exc.from.op.get) &&
								endOpIndex <= ops.indexOf(exc.to.op.get) /*&&
								ops.view(startOpIndex, endOpIndex).exists(_.canThrow)*/
					}).foreach(exc => createVertexFromMarker(currentBlock, exc.target, ThrowEdge[V]))
				}
				buildEdge()
			}
		}
		buildEdge()

		new BytecodeControlFlowGraph(new Graph(edgeMap), entryVertex, exitVertex).optimized
	}
}
