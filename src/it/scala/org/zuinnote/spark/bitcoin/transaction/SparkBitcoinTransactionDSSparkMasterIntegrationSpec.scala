/**
* Copyright 2017 ZuInnoTe (Jörn Franke) <zuinnote@gmail.com>
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
**/

/**
*
* This test intregrates HDFS and Spark
*
*/

package org.zuinnote.spark.bitcoin.transaction


import org.apache.hadoop.hdfs.MiniDFSCluster
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FSDataInputStream
import org.apache.hadoop.fs.Path

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.util.ArrayList
import java.util.List


import org.apache.hadoop.io.compress.CodecPool
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.io.compress.Decompressor
import org.apache.hadoop.io.compress.SplittableCompressionCodec
import org.apache.hadoop.io.compress.SplitCompressionInputStream


import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SQLContext 
import org.apache.spark.sql.functions._


import scala.collection.mutable.ArrayBuffer
import org.scalatest.{FlatSpec, BeforeAndAfterAll, GivenWhenThen, Matchers}

class SparkBitcoinTransactionDSSparkMasterIntegrationSpec extends FlatSpec with BeforeAndAfterAll with GivenWhenThen with Matchers {
 
private var sc: SparkContext = _
private var sqlContext: SQLContext = _
private val master: String = "local[2]"
private val appName: String = "example-scalasparkbitcoinblockcounter-integrationtest"
private val tmpPrefix: String = "hcl-integrationtest"
private var tmpPath: java.nio.file.Path = _
private val CLUSTERNAME: String ="hcl-minicluster"
private val DFS_INPUT_DIR_NAME: String = "/input"
private val DFS_OUTPUT_DIR_NAME: String = "/output"
private val DEFAULT_OUTPUT_FILENAME: String = "part-00000"
private val DFS_INPUT_DIR : Path = new Path(DFS_INPUT_DIR_NAME)
private val DFS_OUTPUT_DIR : Path = new Path(DFS_OUTPUT_DIR_NAME)
private val NOOFDATANODES: Int =4
private var dfsCluster: MiniDFSCluster = _
private var conf: Configuration = _
private var openDecompressors = ArrayBuffer[Decompressor]();

override def beforeAll(): Unit = {
    super.beforeAll()

		// Create temporary directory for HDFS base and shutdownhook 
	// create temp directory
      tmpPath = Files.createTempDirectory(tmpPrefix)
      // create shutdown hook to remove temp files (=HDFS MiniCluster) after shutdown, may need to rethink to avoid many threads are created
	Runtime.getRuntime.addShutdownHook(new Thread("remove temporary directory") {
      	 override def run(): Unit =  {
        	try {
          		Files.walkFileTree(tmpPath, new SimpleFileVisitor[java.nio.file.Path]() {

            		override def visitFile(file: java.nio.file.Path,attrs: BasicFileAttributes): FileVisitResult = {
                		Files.delete(file)
             			return FileVisitResult.CONTINUE
        			}

        		override def postVisitDirectory(dir: java.nio.file.Path, e: IOException): FileVisitResult = {
          			if (e == null) {
            				Files.delete(dir)
            				return FileVisitResult.CONTINUE
          			}
          			throw e
        			}
        	})
      	} catch {
        case e: IOException => throw new RuntimeException("Error temporary files in following path could not be deleted "+tmpPath, e)
    }}})
	// create DFS mini cluster
	 conf = new Configuration()
	val baseDir = new File(tmpPath.toString()).getAbsoluteFile()
	conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, baseDir.getAbsolutePath())
	val builder = new MiniDFSCluster.Builder(conf)
 	 dfsCluster = builder.numDataNodes(NOOFDATANODES).build()
	conf.set("fs.defaultFS", dfsCluster.getFileSystem().getUri().toString()) 
	// create local Spark cluster
 	val sparkConf = new SparkConf()
      .setMaster("local[2]")
      .setAppName(this.getClass.getSimpleName)
	sc = new SparkContext(sparkConf)
	sqlContext = new SQLContext(sc)
 }

  
  override def afterAll(): Unit = {
   // close Spark Context
    if (sc!=null) {
	sc.stop()
    } 
    // close decompressor
	for ( currentDecompressor <- this.openDecompressors) {
		if (currentDecompressor!=null) {
			 CodecPool.returnDecompressor(currentDecompressor)
		}
 	}
    // close dfs cluster
    dfsCluster.shutdown()
    super.afterAll()
}


"The genesis block on DFS" should "be fully read in dataframe" in {
	Given("Genesis Block on DFSCluster")
	// create input directory
	dfsCluster.getFileSystem().mkdirs(DFS_INPUT_DIR)
	// copy bitcoin blocks
	val classLoader = getClass().getClassLoader()
    	// put testdata on DFS
    	val fileName: String="genesis.blk"
    	val fileNameFullLocal=classLoader.getResource("testdata/"+fileName).getFile()
    	val inputFile=new Path(fileNameFullLocal)
    	dfsCluster.getFileSystem().copyFromLocalFile(false, false, inputFile, DFS_INPUT_DIR)	
	When("reading Genesis block using datasource")
	val df = sqlContext.read.format("org.zuinnote.spark.bitcoin.transaction").option("magic", "F9BEB4D9").load(dfsCluster.getFileSystem().getUri().toString()+DFS_INPUT_DIR_NAME)
	Then("all fields should be readable trough Spark SQL")
	// check first if structure is correct
	assert("currentTransactionHash"==df.columns(0))
	assert("version"==df.columns(1))
	assert("inCounter"==df.columns(2))
	assert("outCounter"==df.columns(3))
	assert("listOfInputs"==df.columns(4))
	assert("listOfOutputs"==df.columns(5))
	assert("lockTime"==df.columns(6))
	// validate transaction data
	val currentTransactionHash = df.select("currentTransactionHash").collect
	val currentTransactionHashExpected : Array[Byte] = Array(0x3B.toByte,0xA3.toByte,0xED.toByte,0xFD.toByte,0x7A.toByte,0x7B.toByte,0x12.toByte,0xB2.toByte,0x7A.toByte,0xC7.toByte,0x2C.toByte,0x3E.toByte,0x67.toByte,0x76.toByte,0x8F.toByte,0x61.toByte,
0x7F.toByte,0xC8.toByte,0x1B.toByte,0xC3.toByte,0x88.toByte,0x8A.toByte,0x51.toByte,0x32.toByte,0x3A.toByte,0x9F.toByte,0xB8.toByte,0xAA.toByte,0x4B.toByte,0x1E.toByte,0x5E.toByte,0x4A.toByte)
	assert(currentTransactionHashExpected.deep==currentTransactionHash(0).get(0).asInstanceOf[Array[Byte]].deep)
	val version = df.select("version").collect
	assert(1==version(0).getInt(0))
	val inCounter = df.select("inCounter").collect
	val inCounterExpected: Array[Byte] = Array(0x01.toByte)
	assert(inCounterExpected.deep==inCounter(0).get(0).asInstanceOf[Array[Byte]].deep)
	val outCounter = df.select("outCounter").collect
	val outCounterExpected: Array[Byte] = Array(0x01.toByte)
	assert(outCounterExpected.deep==outCounter(0).get(0).asInstanceOf[Array[Byte]].deep)
	val transactionsLockTime=df.select("lockTime").collect
	assert(0==transactionsLockTime(0).getInt(0))
	val transactionsLOIDF = df.select(explode(df("listOfInputs")).alias("listOfInputs"))
	val prevTransactionHash = transactionsLOIDF.select("listOfInputs.prevTransactionHash").collect
	val prevTransactionHashExpected: Array[Byte] = Array(0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte,0x00.toByte)
	assert(prevTransactionHashExpected.deep==prevTransactionHash(0).get(0).asInstanceOf[Array[Byte]].deep)
	val previousTxOutIndex = transactionsLOIDF.select("listOfInputs.previousTxOutIndex").collect
	assert(4294967295L==previousTxOutIndex(0).getLong(0))
	val txInScriptLength = transactionsLOIDF.select("listOfInputs.txInScriptLength").collect
	val txInScriptLengthExpected: Array[Byte] = Array(0x4D.toByte)
	assert(txInScriptLengthExpected.deep==txInScriptLength(0).get(0).asInstanceOf[Array[Byte]].deep)
	val txInScript = transactionsLOIDF.select("listOfInputs.txInScript").collect
	val txInScriptExpected: Array[Byte] =Array(0x04.toByte,0xFF.toByte,0xFF.toByte,0x00.toByte,0x1D.toByte,0x01.toByte,0x04.toByte,0x45.toByte,0x54.toByte,0x68.toByte,0x65.toByte,0x20.toByte,0x54.toByte,0x69.toByte,0x6D.toByte,0x65.toByte,
0x73.toByte,0x20.toByte,0x30.toByte,0x33.toByte,0x2F.toByte,0x4A.toByte,0x61.toByte,0x6E.toByte,0x2F.toByte,0x32.toByte,0x30.toByte,0x30.toByte,0x39.toByte,0x20.toByte,0x43.toByte,0x68.toByte,
0x61.toByte,0x6E.toByte,0x63.toByte,0x65.toByte,0x6C.toByte,0x6C.toByte,0x6F.toByte,0x72.toByte,0x20.toByte,0x6F.toByte,0x6E.toByte,0x20.toByte,0x62.toByte,0x72.toByte,0x69.toByte,0x6E.toByte,0x6B.toByte,
0x20.toByte,0x6F.toByte,0x66.toByte,0x20.toByte,0x73.toByte,0x65.toByte,0x63.toByte,0x6F.toByte,0x6E.toByte,0x64.toByte,0x20.toByte,0x62.toByte,0x61.toByte,0x69.toByte,0x6C.toByte,0x6F.toByte,
0x75.toByte,0x74.toByte,0x20.toByte,0x66.toByte,0x6F.toByte,0x72.toByte,0x20.toByte,0x62.toByte,0x61.toByte,0x6E.toByte,0x6B.toByte,0x73.toByte)
	assert(txInScriptExpected.deep==txInScript(0).get(0).asInstanceOf[Array[Byte]].deep)
	val seqNo = transactionsLOIDF.select("listOfInputs.seqNo").collect
	assert(4294967295L==seqNo(0).getLong(0))
	val transactionsLOODF = df.select(explode(df("listOfOutputs")).alias("listOfOutputs"))
	val value = transactionsLOODF.select("listOfOutputs.value").collect
	assert(5000000000L==value(0).getLong(0))
	val txOutScriptLength = transactionsLOODF.select("listOfOutputs.txOutScriptLength").collect
	val txOutScriptLengthExpected: Array[Byte] = Array(0x43.toByte)
	assert(txOutScriptLengthExpected.deep==txOutScriptLength(0).get(0).asInstanceOf[Array[Byte]].deep)
	val txOutScript = transactionsLOODF.select("listOfOutputs.txOutScript").collect
	val txOutScriptExpected: Array[Byte] = Array(0x41.toByte,0x04.toByte,0x67.toByte,0x8A.toByte,0xFD.toByte,0xB0.toByte,0xFE.toByte,0x55.toByte,0x48.toByte,0x27.toByte,0x19.toByte,0x67.toByte,0xF1.toByte,0xA6.toByte,0x71.toByte,0x30.toByte,
0xB7.toByte,0x10.toByte,0x5C.toByte,0xD6.toByte,0xA8.toByte,0x28.toByte,0xE0.toByte,0x39.toByte,0x09.toByte,0xA6.toByte,0x79.toByte,0x62.toByte,0xE0.toByte,0xEA.toByte,0x1F.toByte,0x61.toByte,
0xDE.toByte,0xB6.toByte,0x49.toByte,0xF6.toByte,0xBC.toByte,0x3F.toByte,0x4C.toByte,0xEF.toByte,0x38.toByte,0xC4.toByte,0xF3.toByte,0x55.toByte,0x04.toByte,0xE5.toByte,0x1E.toByte,0xC1.toByte,
0x12.toByte,0xDE.toByte,0x5C.toByte,0x38.toByte,0x4D.toByte,0xF7.toByte,0xBA.toByte,0x0B.toByte,0x8D.toByte,0x57.toByte,0x8A.toByte,0x4C.toByte,0x70.toByte,0x2B.toByte,0x6B.toByte,0xF1.toByte,
0x1D.toByte,0x5F.toByte,0xAC.toByte)
	assert(txOutScriptExpected.deep==txOutScript(0).get(0).asInstanceOf[Array[Byte]].deep)
}



}