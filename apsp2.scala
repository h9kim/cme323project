// [CHARLES] Interactive port of spark-all-pairs-shortest-path for educational purposes
// [CHARLES] Replaces use of GridPartitioner with HashPartitioner

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.mllib.linalg.{SparseMatrix, DenseMatrix, Matrix}
import org.apache.spark.mllib.linalg.distributed.{CoordinateMatrix, MatrixEntry, BlockMatrix}
import org.apache.spark.rdd.RDD
import breeze.linalg.{DenseMatrix => BDM, sum, DenseVector, min}
import org.apache.spark.graphx._
import org.apache.spark.graphx.util.GraphGenerators
import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.apache.spark.HashPartitioner

sc.setCheckpointDir("checkpoint/")

// [CHARLES] The following code did not need to be changed, except reformatting tabs

def generateGraph(n: Int, sc: SparkContext): Graph[Long, Double] = {
  val graph = GraphGenerators.logNormalGraph(sc, n).mapEdges(e => e.attr.toDouble)
  graph
}

def fromBreeze(dm: BDM[Double]): Matrix = {
  new DenseMatrix(dm.rows, dm.cols, dm.toArray, dm.isTranspose)
}

def toBreeze(A: Matrix): BDM[Double] = {
  new BDM[Double](A.numRows, A.numCols, A.toArray)
}

def localMinPlus(A: BDM[Double], B: BDM[Double]): BDM[Double] = {
  require(A.cols == B.rows, " Num cols of A does not match the num rows of B")
  val k = A.cols
  val onesA = DenseVector.ones[Double](B.cols)
  val onesB = DenseVector.ones[Double](A.rows)
  var AMinPlusB = A(::, 0) * onesA.t + onesB * B(0, ::)
  if (k > 1) {
    for (i <- 1 until k) {
      val a = A(::, i)
      val b = B(i, ::)
      val aPlusb = a * onesA.t + onesB * b
      AMinPlusB = min(aPlusb, AMinPlusB)
    }
  }
  AMinPlusB
}

def localFW(A: BDM[Double]): BDM[Double] = {
  require(A.rows == A.cols, "Matrix for localFW should be square!")
  var B = A
  val onesA = DenseVector.ones[Double](A.rows)
  for (i <- 0 until A.rows) {
    val a = B(::, i)
    val b = B(i, ::)
    B = min(B, a * onesA.t + onesA * b)
  }
  B
}

def addInfinity(A: SparseMatrix, rowBlockID: Int, colBlockID: Int): Matrix = {
  val inf = scala.Double.PositiveInfinity
  val result: BDM[Double] = BDM.tabulate(A.numRows, A.numCols){case (i, j) => inf}
  for (j <- 0 until A.values.length)
    for (i <- 0 until A.numCols) {
      if (j >= A.colPtrs(i) & j < A.colPtrs(i + 1))
        result(A.rowIndices(j), i) = A.values(j)
    }
  if (rowBlockID == colBlockID) {
    require(A.numCols == A.numRows, "Diagonal block should have a square matrix")
    for (i <- 0 until A.numCols)
      result(i, i) = 0.0
  }
  fromBreeze(result)
}

// [CHARLES] Replaced references of GridPartitioner from the following code

// [CHARLES] ADDED ApspPartitioner here
def generateInput(graph: Graph[Long,Double], n: Int, sc:SparkContext,
                    RowsPerBlock: Int, ColsPerBlock: Int, ApspPartitioner: HashPartitioner): BlockMatrix = {
  val entries = graph.edges.map { case edge => MatrixEntry(edge.srcId.toInt, edge.dstId.toInt, edge.attr) }
  val coordMat = new CoordinateMatrix(entries, n, n)
  val matA = coordMat.toBlockMatrix(RowsPerBlock, ColsPerBlock)
  require(matA.numColBlocks == matA.numRowBlocks)
  // make sure that all block indices appears in the matrix blocks
  // add the blocks that are not represented
  val activeBlocks: BDM[Int] = BDM.zeros[Int](matA.numRowBlocks, matA.numColBlocks)
  val activeIdx = matA.blocks.map { case ((i, j), v) => (i, j) }.collect()
  activeIdx.foreach { case (i, j) => activeBlocks(i, j) = 1 }
  val nAddedBlocks = matA.numRowBlocks * matA.numColBlocks - sum(activeBlocks)
  // recognize which blocks need to be added
  val addedBlocksIdx = Array.range(0, nAddedBlocks).map(i => (0, i))
  var index = 0
  for (i <- 0 until matA.numRowBlocks) {
    for (j <- 0 until matA.numColBlocks) {
      if (activeBlocks(i, j) == 0) {
        addedBlocksIdx(index) = (i, j)
        index = index + 1
      }
    }
  }
  // Create empty blocks with just the non-represented block indices
  val addedBlocks = sc.parallelize(addedBlocksIdx).map { case (i, j) => {
    var nRows = matA.rowsPerBlock
    var nCols = matA.colsPerBlock
    if (i == matA.numRowBlocks - 1) nRows = matA.numRows().toInt - nRows * (matA.numRowBlocks - 1)
    if (j == matA.numColBlocks - 1) nCols = matA.numCols().toInt - nCols * (matA.numColBlocks - 1)
    val newMat: Matrix = new SparseMatrix(nRows, nCols, BDM.zeros[Int](1, nCols + 1).toArray,
      Array[Int](), Array[Double]())
    ((i, j), newMat)
  }
  }
  val initialBlocks = addedBlocks.union(matA.blocks).partitionBy(ApspPartitioner)
  val blocks: RDD[((Int, Int), Matrix)] = initialBlocks.map { case ((i, j), v) => {
    val converted = v match {
      case dense: DenseMatrix => dense
      case sparse: SparseMatrix => addInfinity(sparse, i, j)
    }
    ((i, j), converted)
  }
  }
  new BlockMatrix(blocks, matA.rowsPerBlock, matA.colsPerBlock, n, n)
}

def blockMin(Ablocks: RDD[((Int, Int), Matrix)], Bblocks: RDD[((Int, Int), Matrix)],
             ApspPartitioner: HashPartitioner): RDD[((Int, Int), Matrix)] = {
  val addedBlocks = Ablocks.join(Bblocks, ApspPartitioner).mapValues {
    case (a, b) => fromBreeze(min(toBreeze(a), toBreeze(b)))
  }
  addedBlocks
}


def blockMinPlus(Ablocks: RDD[((Int, Int), Matrix)], Bblocks: RDD[((Int, Int), Matrix)],
                 numRowBlocks: Int, numColBlocks: Int,
                 ApspPartitioner: HashPartitioner): RDD[((Int, Int), Matrix)] = {
  // Each block of A must do cross plus with the corresponding blocks in each column of B.
  // TODO: Optimize to send block to a partition once, similar to ALS
  val flatA = Ablocks.flatMap { case ((blockRowIndex, blockColIndex), block) =>
    Iterator.tabulate(numColBlocks)(j => ((blockRowIndex, j, blockColIndex), block))
  }
  // Each block of B must do cross plus with the corresponding blocks in each row of A.
  val flatB = Bblocks.flatMap { case ((blockRowIndex, blockColIndex), block) =>
    Iterator.tabulate(numRowBlocks)(i => ((i, blockColIndex, blockRowIndex), block))
  }
  val newBlocks = flatA.join(flatB, ApspPartitioner)
    .map { case ((blockRowIndex, blockColIndex, _), (a, b)) =>
      val C = localMinPlus(toBreeze(a), toBreeze(b))
      ((blockRowIndex, blockColIndex), C)
    }.reduceByKey(ApspPartitioner, (a, b) => min(a, b))
    .mapValues(C => fromBreeze(C))
  return newBlocks
}


def distributedApsp(A: BlockMatrix, stepSize: Int, ApspPartitioner: HashPartitioner,
                    sc: SparkContext): BlockMatrix = {
  require(A.numRows() == A.numCols(), "The adjacency matrix must be square.")
  require(A.numRowBlocks == A.numColBlocks, "The blocks making up the adjacency matrix must be square.")
  require(stepSize <= A.rowsPerBlock, "Step size must be less than number of rows in a block.")
  val n = A.numRows()
  val niter = math.ceil(n * 1.0 / stepSize).toInt
  var apspRDD = A.blocks
  var rowRDD : RDD[((Int, Int), Matrix)] = null
  var colRDD : RDD[((Int, Int), Matrix)] = null

  for (i <- 0 to (niter - 1)) {
    if (i % 20 == 0) {
      apspRDD.checkpoint()
      apspRDD.count()
    }
    val StartBlock = i * stepSize / A.rowsPerBlock
    val EndBlock = math.min((i + 1) * stepSize - 1, n - 1) / A.rowsPerBlock
    val startIndex = i * stepSize - StartBlock * A.rowsPerBlock
    val endIndex =  (math.min((i + 1) * stepSize - 1, n - 1) - EndBlock * A.rowsPerBlock).toInt
    if (StartBlock == EndBlock) {
      // Calculate the APSP of the square matrix
      val squareMat = apspRDD.filter(kv => (kv._1._1 == StartBlock) && (kv._1._2 == StartBlock))
        .mapValues(localMat => fromBreeze(localFW(toBreeze(localMat)(startIndex to endIndex, startIndex to endIndex))))
        .first._2
      val x = sc.broadcast(squareMat)
      // the rowRDD updated by squareMat
      rowRDD = apspRDD.filter(_._1._1 == StartBlock)
        .mapValues(localMat => fromBreeze(localMinPlus(toBreeze(x.value),
                                                       toBreeze(localMat)(startIndex to endIndex, ::))))
      // the colRDD updated by squareMat
      colRDD  = apspRDD.filter(_._1._2 == StartBlock)
        .mapValues(localMat => fromBreeze(localMinPlus(toBreeze(localMat)(::, startIndex to endIndex),
                                                       toBreeze(x.value))))
    } else {
      // this is the case when the filtered slice doesn't fall into one block
      // we required StartBlock >= EndBlock - 1
      // current implementation involves very complicated operations (should be simplified)!!!
      // also need to deal with the case that the actual slice size might be smaller (for the end slice)
      val squareMatArray = apspRDD.filter(kv => (kv._1._1 == StartBlock || kv._1._1 == EndBlock)
                                            && (kv._1._2 == StartBlock || kv._1._2 == EndBlock))
        .map { case ((i, j), localMat) =>
        (i, j) match {
          case (StartBlock, StartBlock) =>
            ((i, j), fromBreeze(toBreeze(localMat)(startIndex until localMat.numRows, startIndex until localMat.numCols)))
          case (StartBlock, EndBlock) =>
            ((i, j), fromBreeze(toBreeze(localMat)(startIndex until localMat.numRows, 0 to endIndex)))
          case (EndBlock, StartBlock) =>
            ((i, j), fromBreeze(toBreeze(localMat)(0 to endIndex, startIndex until localMat.numCols)))
          case (EndBlock, EndBlock) =>
            ((i, j), fromBreeze(toBreeze(localMat)(0 to endIndex, 0 to endIndex)))
          }
        }.collect()

      // calculate the actual square matrix size
      val size1 = squareMatArray.filter(_._1 == (StartBlock, EndBlock))(0)._2.numRows
      val size2 = squareMatArray.filter(_._1 == (StartBlock, EndBlock))(0)._2.numCols
      // covert the square matrix Array to a local BDM matrix
      val tempMat = BDM.zeros[Double](size1 + size2, size1 + size2)

      squareMatArray.foreach{case ((i, j), v) =>
        tempMat((size1 * (i - StartBlock)) until (size1 + size2 * (i - StartBlock)),
                (size1 * (j - StartBlock)) until (size1 + size2 * (j - StartBlock))) := toBreeze(v)}
      val squareMat = localFW(tempMat)
      // convert the local BDM matrix back to a square matrix RDD
      val squareMatRDD = sc.parallelize(squareMatArray.map{case ((i, j), v) =>
        ((i, j), fromBreeze(squareMat((size1 * (i - StartBlock)) until (size1 + size2 * (i - StartBlock)),
                                      (size1 * (j - StartBlock)) until (size1 + size2 * (j - StartBlock)))))})

      rowRDD = blockMinPlus(squareMatRDD, apspRDD.filter(kv => kv._1._1 == StartBlock || kv._1._1 == EndBlock)
        .map { case ((i, j), localMat) =>
          i match {
            case StartBlock =>
              ((i, j), fromBreeze(toBreeze(localMat)(startIndex until localMat.numRows, ::)))
            case EndBlock =>
              ((i, j), fromBreeze(toBreeze(localMat)(0 to endIndex, ::)))
          }
        }, 2, A.numColBlocks, ApspPartitioner)
      colRDD = blockMinPlus(apspRDD.filter(kv => kv._1._2 == StartBlock || kv._1._2 == EndBlock)
        .map { case ((i, j), localMat) =>
          j match {
            case StartBlock =>
              ((i, j), fromBreeze(toBreeze(localMat)(::, startIndex until localMat.numCols)))
            case EndBlock =>
              ((i, j), fromBreeze(toBreeze(localMat)(::, 0 to endIndex)))
          }
        }, squareMatRDD, A.numRowBlocks, 2, ApspPartitioner)
    }

    apspRDD = blockMin(apspRDD, blockMinPlus(colRDD, rowRDD, A.numRowBlocks, A.numColBlocks, ApspPartitioner),
                ApspPartitioner)
  }
  new BlockMatrix(apspRDD, A.rowsPerBlock, A.colsPerBlock, n, n)
}


// [CHARLES] Main code
    
val n = 8
val m = 4
val stepSize = 2
val graph = generateGraph(n, sc)
val ApspPartitioner = new HashPartitioner(4)
val matA = generateInput(graph, n, sc, m, m, ApspPartitioner)
val localMat = matA.toLocalMatrix()

val resultMat = distributedApsp(matA, stepSize, ApspPartitioner, sc)
val ans = resultMat.toLocalMatrix()
val localAns = localFW(toBreeze(localMat))


// [CHARLES] lookup
import org.apache.spark.mllib.random.UniformGenerator
val nlook = m
val r = new UniformGenerator()
val seed = 1398
r.setSeed(seed)
val ids = (for (i <- 0 to nlook-1) yield(r.nextValue())).zipWithIndex.sortBy(_._1).map(_._2)
var lala = ids
val time1 : Long = System.currentTimeMillis
val res = for (i <- 0 to (nlook - 1)) yield {
  val a = i % 2
  val b = (i/2) % 2
  val temp = matA.blocks.filter(_._1 == (a, b)).map( _._2.toArray(ids(i)) ).collect
  temp(0)
}
val time2 : Long = System.currentTimeMillis
val time : Double = (time2 - time1).toFloat / 1000
