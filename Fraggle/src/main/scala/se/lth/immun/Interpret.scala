package se.lth.immun

import se.jt.Params
import se.jt.CLIApp
import java.io.File

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.io.FileReader
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet
import scala.util.{Either, Left, Right, Try, Failure, Success}

import se.lth.immun.xml.XmlReader
import se.lth.immun.mzml.MzML
import se.lth.immun.mzml.Spectrum
import se.lth.immun.mzml.SelectedIon
import se.lth.immun.mzml.MzMLDataHandlers
import se.lth.immun.mzml.ghost.GhostSpectrum

import se.lth.immun.protocol._
import se.lth.immun.protocol.MSFragmentationProtocol.FragmentType
import se.lth.immun.protocol.MSFragmentationProtocol.PrecursorType
import se.lth.immun.unimod.UniMod
import se.lth.immun.chem.Ion
import se.lth.immun.chem.Peptide
import se.lth.immun.chem.Constants
import se.lth.immun.chem.PeptideUtil
import se.lth.immun.chem.PeptideFragment
import se.lth.immun.chem.EPeptideFragment

import FraggleIntermediary._



object Interpret extends Command with CLIApp {
	val MS_LEVEL_ACC = "MS:1000511"
	val SELECTED_ION_MZ_ACC = "MS:1000744"
	val CHARGE_STATE_ACC = "MS:1000041"
	val PEAK_INTENSITY_ACC = "MS:1000042"
	val COLLISION_ENERGY_ACC = "MS:1000045"
	val HCD_ACC = "MS:1000422"
	val CID_ACC = "MS:1000133"
	val ETD_ACC = "MS:1000598"
	
	
	trait SpectrumID { def scan:Int }
	case class ScanID(scan:Int, id:String) extends SpectrumID
	case class IndexID(scan:Int, id:String) extends SpectrumID
	case class AbSciexID(
			scan:Int, 
			sample:Int, 
			period:Int, 
			cycle:Int, 
			experiment:Int
		) extends SpectrumID
	case class DemixOrigID(
			val scan:Int, 
			val precInt:Double, 
			val rtInSec:Double, 
			val precRank:Int
		) extends SpectrumID
	case class DemixFeatID(
			val scan:Int, 
			val precInt:Double, 
			val rtInSec:Double, 
			val featApexInt:Double, 
			val precRank:Int
		) extends SpectrumID
	case class DemixComplFragID(
			val scan:Int, 
			val precRank:Int
		) extends SpectrumID
	
	trait ID {
		def specID:SpectrumID
		def precursorMz:Double
		def z:Int
		def pepSequence:String
		def protein:String
		def qValue:Double
		def score:Double
		def psmLevelOk:Boolean
	}
	
	
	trait IDs {
		def fromFile(f:File, params:InterpretParams):Seq[ID]
		def formatName:String
	}
	
	class InterpretParams extends Params {
		import Params._
		
		val mzML 		= ReqString("mzML to create fraggle file from")
		val identTsv 	= ReqString("ident tsv to create fraggle file from")
		val irt		 	= ReqString("irt peptides to create rt -> irt map")
		
		val fragMatchTol = "10.0ppm" 	## "Tolerance for fragment interpretation [Xppm or XDa]"
		val fragMatchMode = "closest" 	## "Mode for fragment interpretation [closest (default), greedy or uniform]"
		val fragMaxCharge = 2 			## "Maximal fragment charge to consider"
		val minInternalLength = 2		## "Min length of internal ions to consider"
		val maxInternalLength = 6		## "Max length of internal ions to consider"
		val psmFDR = 1.0				## "The PSM level FDR cutoff to apply on loaded identifications"
		val eValue = 1.0				## "The eValue cutoff to apply on MS-GF+ identifications"
		val peptideProb = 0.0			## "The peptide/inter probability cutoff to apply on loaded Peptide Csv identifications. Note that interprophet probabilities will be used if present."
		val irtR2 = 0.9					## "r2 threshold required for regression iRT maps. Will fail if this level is not met."
		val irtMode = "weight-mean-ipolate"		## "The iRT mapping algorithm to use: simple-reg, robust-reg, median-ipolate or weight-mean-ipolate"
		val irtNAnchors = 5				## "The number of anchor points (iRT peptides) required for interpolating iRT maps. Will fail if this number is not met."
		val defaultCollisionEnergy = 0.0	## "The default collision energy to use if not annotated (5600 mzML files). If 0 and ce not found, will report error and exit."
		
		val excludeProtPrefix = "DECOY"	## "Proteins with this prefix will be ignored"
		val excludeMode = "full"		## "How much to exclude by ProtPrefix or thresholds (full or primary)"
		val useFeatureApexRT = false 	## "Use MS1 feature apex rt when available (parsed from spectrum id)"
		
		val outDir			= ""		## "output directory (by default same as input mzML)"
		val outName			= ""		## "basename for output files (by default same as input mzML)"
		val verbose 		= false		## "set to enable a lot of output"
		val writeTsv		= false		## "set to write tsv instead of fragment binary file"
		val tsvFragN		= -1		## "number of most intense fragments to output upon writeTsv (-1 means all are written)"
		
		lazy val fragThreshold = {
			if (fragMatchTol.toLowerCase.endsWith("ppm")) Interpretation.PPMThreshold(fragMatchTol.value.dropRight(3).toDouble)
			else if (fragMatchTol.toLowerCase.endsWith("da")) Interpretation.AbsThreshold(fragMatchTol.value.dropRight(2).toDouble)
			else throw new Exception("Invalid fragment tolerance '%s'. Needs to be Xppm or XDa".format(fragMatchTol.value))
		}
		
		lazy val fragInterpret = 
			fragMatchMode.value match {
				case "closest" => Interpretation.advanced(meta => new Interpretation.Closest(meta)) _
				case "uniform" => Interpretation.advanced(meta => new Interpretation.Uniform(meta)) _
				case "greedy" => Interpretation.naive _
			}
		
		def outBase = {
			val identFile = new File(identTsv)
			val dir = 
				if (outDir.value != "") outDir.value
				else identFile.getParent
			val name =
				if (outName.value != "") outName.value
				else stripExts(identFile.getName)
			(dir, name) 
		}
		
		def outFile = {
			val (dir, name) = outBase
			new File(dir, name + ".fragments.bin")
		}
		
		def outTsv = { 
			val (dir, name) = outBase
			new File(dir, name + ".fragments.tsv")
		}
		
		def outIrtMap = {
			val (dir, name) = outBase
			new File(dir, name + "."+irtMode.value+".irtmap")
		}
		
		def outIrtMapPlot = {
			val (dir, name) = outBase
			new File(dir, name + "."+irtMode.value+".irtmap.png")
		}
		
		def stripExt(path:String, ext:String) =
			if (path.toLowerCase.endsWith(ext.toLowerCase))
				path.dropRight(ext.length)
			else path
		
		def stripExts(path:String) =
			stripExt(stripExt(stripExt(path, ".gz"), ".tsv"), ".mzML")
			
		def mzMLBaseName = 
			new File(stripExts(mzML)).getName
	
	def computeIRTMap = 
		irtMode.value match {
			case "median-ipolate" => IRT.medianInterpolationMap _
			case "weight-mean-ipolate" => IRT.weightedMeanInterpolationMap _
			case "simple-reg" => IRT.simpleRegressionMap _
			case "robust-reg" => IRT.robustRegressionMap _
		}
	}
	
	
	

	val desc = "Interpret ms2 spectra in mzML using MSGF+ search results and save in fragment file"
	val params = new InterpretParams
	
	def execute(name:String, version:String, command:String, args:Array[String]) = {
		
		failOnError(parseArgs(name, version, args, params, List("mzML", "identTsv", "irt"), None))
		
		printHeader(command)
		
		status("reading iRT peptides...")
		val irtPeptides = IRT.readPeptideTsv(new File(params.irt))
		
		
		val format = guessFormat(params.identTsv)
		status("reading identifications from %s...".format(format.formatName))
		val idsByScan = 
			format.fromFile(new File(params.identTsv), params)
				.sortBy(_.specID.scan)
				.toArray
		info("read %d IDs of %d unique peptides".format(idsByScan.length, idsByScan.map(_.pepSequence).distinct.length))
		
		status("interpreting mzML...")
		val rawAAMolecules = parseMzML(new File(params.mzML), idsByScan)
		val obses = rawAAMolecules.flatMap(_.observations)
		val wholeObses = obses.filter(_.x.fragments.nonEmpty)
		info("interpreted %d IDs from %d spectra".format(wholeObses.length, interpretedSpectra.size))
		params.excludeMode.value match {
			case "full" =>
				info(" excluded %d IDs based of protein exclusion and thresholds".format(idsByScan.length - obses.length))
			case "primary" =>
				info(" included %d meta data IDs".format(obses.length - wholeObses.length))
			case x =>
				throw new Exception("Unknown exclude mode '%s'".format(x))
		}
		
		status("extracting iRT peptide identifications...")
		val irtDataPoints = FraggleIRT.findDataPoints(irtPeptides, rawAAMolecules)
		status("building iRT map...")
		def irtFail(msg:String) =
			new Exception("Failure creating iRT map: "+msg)
		val irtMap = 
			Try(params.computeIRTMap(irtDataPoints)) match {
				case Success(map) => map
				case Failure(e) =>
					e match {
						case nde:org.apache.commons.math3.exception.NoDataException =>
							throw irtFail("not enough iRT datapoints, n=%d\n  IRT DATAPOINTS:\n%s".format(
									irtDataPoints.length,
									irtDataPoints.mkString("\n  ")
								))
						case e:Exception =>
							throw irtFail(e.getMessage)
					}
			}
		
		
		info("IRT MAP:")
		irtMap.writeMapParams(print)
		
		status("writing irtMap to '%s'...".format(params.outIrtMap))
		irtMap.toFile(params.outIrtMap)
		status("writing irtMap plot to '%s'...".format(params.outIrtMapPlot))
		irtMap.plot(params.outIrtMapPlot)
		
		for (errMsg <- irtMap.anythingWrong(params.irtR2, params.irtNAnchors))
			throw irtFail(errMsg)
			
		status("calculating iRTs...")
		val aaMolecules:Seq[AAMolecule] = rawAAMolecules.map(FraggleIRT.map(irtMap.predict))
		
		if (params.writeTsv) {
			status("writing output tsv...")
			FragmentTsv.write(params.outTsv, aaMolecules, params.tsvFragN)
			info("wrote to '%s'".format(params.outTsv))
		} else {
			status("writing output binary...")
			MsFragmentationFile.write(params.outFile, aaMolecules, params.verbose)
			info("wrote to '%s'".format(params.outFile))
		}
		status("done")
	}
	
	
	
	def parseMzML(f:File, byScan:Array[ID]) = {	
		val aaMolecules = new ArrayBuffer[RawAAMolecule]
		val dh = new MzMLDataHandlers(
			n => {},
			handleSpectrum(aaMolecules, byScan),
			n => {},
			c => {})
		
		MzML.fromFile(getReader(f), dh, null)
		aaMolecules
	}
	
	
	
	var idIndex = 0
	var interpretedSpectra = new HashSet[Int]
	def handleSpectrum(
			aaMolecules:ArrayBuffer[RawAAMolecule],
			byScan:Array[ID]
	)(
			s:Spectrum
	) = {
		
		val msLevel = s.cvParams.find(_.accession == MS_LEVEL_ACC).map(_.value.get.toInt)
		val specID = parseSpectrumID(s.index, s.id)
		
		lazy val gs = GhostSpectrum.fromSpectrum(s)
		while (idIndex < byScan.length && byScan(idIndex).specID.scan <= specID.scan) {
			
			if (byScan(idIndex).specID.scan == specID.scan) {
				msLevel match {
					case Some(x) =>
						if (x != 2)
							throw new Exception("[SCAN_NUM:%d] Will only match ids to spectra with ms level 2, got %d".format(specID.scan, x))
					case None =>
						throw new Exception("[SCAN_NUM:%d] No msLevel annotation found in spectrum!".format(specID.scan))
				}
				
				// check centroiding!!
				
				try {
					val id = byScan(idIndex)
					if (id.psmLevelOk && !id.protein.startsWith(params.excludeProtPrefix))
						aaMolecules += interpret(gs, id)
					else if (params.excludeMode.value == "primary")
						aaMolecules += parseMeta(gs, id)
							
					interpretedSpectra += idIndex
				} catch {
					case e:Exception =>
						println("[SPEC:%d %s] Failed interpretation".format(specID.scan, s.id))
						e.printStackTrace
				}
			}
			
			idIndex += 1
		}
	}
	
	
	
	def interpret(gs:GhostSpectrum, id:ID):RawAAMolecule = {
		
		import EPeptideFragment._
		
		val pep = UniMod.parseUniModSequence(id.pepSequence)
		val possFragIons:Seq[(Double, Either[Ion[PeptideFragment], Interpretation.InternalIon])] = 
			PeptideUtil.possibleIons(pep, Array(a,b,c,x,y,z), params.fragMaxCharge.value).map(x => (x.mz, Left(x))) ++
			ionize(genInternalFragments(pep, params.minInternalLength, params.maxInternalLength), params.fragMaxCharge).map(x => (x.mz, Right(x)))
				
		val fragIons = Interpretation.naive(gs, possFragIons.sortBy(_._1), params.fragThreshold)		
		
		val observations =
			if (fragIons.isEmpty) Array[RawObservation]()
			else {
				val (ft, ce) = parseFragmentationType(gs.spectrum)
				val fragBaseIntensity = fragIons.map(_.base.intensity).max
				val (rt, apexInt, precType, precRank) = 
					id.specID match {
						case DemixFeatID(scan, precInt, rt, apexInt, precRank) => 
							(rt, Some(apexInt), PrecursorType.FEAT, precRank)
						case DemixOrigID(scan, precInt, rt, precRank) => 
							(rt, Some(precInt), PrecursorType.ORIG, precRank)
						case DemixComplFragID(scan, precRank) =>
							(gs.scanStartTime, None, PrecursorType.COMPL_FRAG, precRank)
						case _ =>
							(gs.scanStartTime, None, PrecursorType.ORIG, 1)
					}
				
				Array(RawObservation(
						Observation(
							ft,
							id.z,
							ce,
							Some(id.precursorMz),
							parsePrecursorIntensity(gs.spectrum, id.precursorMz, id.z),
							None,
							None,
							Some(fragBaseIntensity),
							Some(id.qValue),
							Some(fragIons.map(_.base.intensity).sum / gs.intensities.sum),
							None,
							Some(precType),
							Some(precRank),
							apexInt,
							Some(id.score),
							normalize(fragIons, fragBaseIntensity)
						),
						if (params.useFeatureApexRT) rt else gs.scanStartTime
					))
			}
		
		
		
		RawAAMolecule(
				id.pepSequence, 
				id.protein,
				pep.monoisotopicMass, 
				observations
			)
	}
	
	
	
	def parseMeta(gs:GhostSpectrum, id:ID):RawAAMolecule = {
		val (ft, ce) = parseFragmentationType(gs.spectrum)
		val (rt, apexInt, precType, precRank) = 
			id.specID match {
				case DemixFeatID(scan, precInt, rt, apexInt, precRank) => 
					(rt, Some(apexInt), PrecursorType.FEAT, precRank)
				case DemixOrigID(scan, precInt, rt, precRank) => 
					(rt, None, PrecursorType.ORIG, precRank)
				case DemixComplFragID(scan, precRank) =>
					(gs.scanStartTime, None, PrecursorType.COMPL_FRAG, precRank)
				case _ =>
					(gs.scanStartTime, None, PrecursorType.ORIG, 1)
			}
		val pep = UniMod.parseUniModSequence(id.pepSequence)
		RawAAMolecule(
				id.pepSequence, 
				id.protein,
				pep.monoisotopicMass, 
				Array(
					RawObservation(
						Observation(
							ft,
							id.z,
							ce,
							Some(id.precursorMz),
							parsePrecursorIntensity(gs.spectrum, id.precursorMz, id.z),
							None,
							None,
							None,
							Some(id.qValue),
							None,
							None,
							Some(precType),
							Some(precRank),
							apexInt,
							Some(id.score),
							Nil
						),
						if (params.useFeatureApexRT) rt else gs.scanStartTime
					)
				)
			)
	}
	
	
	
	def normalize(fragIons:Seq[FragmentAnnotation], base:Double) =
		for (fi <- fragIons) yield
			fi match {
				case SimpleFragment(bf, ftype, ordinal) =>
					SimpleFragment(bf.normalizeBy(base), ftype, ordinal)
				case XLinkFragment(bf, ftype, ordinal, pep) =>
					XLinkFragment(bf.normalizeBy(base), ftype, ordinal, pep)
				case InternalFragment(bf, first, last) =>
					InternalFragment(bf.normalizeBy(base), first, last)
			}
	
	
	def genInternalFragments(pep:Peptide, minLength:Int, maxLength:Int) = {
		val aas = pep.aminoAcids
		val aaMasses = aas.map(_.monoisotopicMass)
		for {
			i <- 1 until aas.length - minLength
			j <- i+minLength until math.min(aas.length, i + maxLength)
		} yield (i, j, aaMasses.slice(i, j).sum)
	}
	
	
	def ionize(frags:Seq[(Int, Int, Double)], maxCharge:Int):Seq[Interpretation.InternalIon] = {
		for {
			z <- 1 to maxCharge 
			(i, j, m) <- frags
		} yield Interpretation.InternalIon(i, j-1, m / z + Constants.PROTON_WEIGHT, z)
	}
	
	
	def selectedIon2Mz(si:SelectedIon):Option[(Double, Int, Double)] = 
		 for {
			 mzCV 	<- si.cvParams.find(_.accession == SELECTED_ION_MZ_ACC)
			 zCV 	<- si.cvParams.find(_.accession == CHARGE_STATE_ACC)
			 intCV 	<- si.cvParams.find(_.accession == PEAK_INTENSITY_ACC)
		 } yield (mzCV.value.get.toDouble, zCV.value.get.toInt, intCV.value.get.toDouble)
	
	
	def parsePrecursorIntensity(s:Spectrum, idMz:Double, idz:Int) = 
		(for {
			p <- s.precursors
			si <- p.selectedIons
			(mz, z, int) <- selectedIon2Mz(si)
			if idMz == mz && idz == z
		} yield int).headOption
		 
	
	def parseFragmentationType(s:Spectrum):(MSFragmentationProtocol.FragmentationType, Double) = {
		import MSFragmentationProtocol.FragmentationType._
		
		val a = s.precursors.head.activation
		val ft =
			if (a.cvParams.exists(_.accession == HCD_ACC)) HCD
			else if (a.cvParams.exists(_.accession == CID_ACC)) CID
			else if (a.cvParams.exists(_.accession == ETD_ACC)) ETD
			else
				throw new Exception("Unknown activation of spectrum '%s'".format(s.id))
		
		val ce =
			a.cvParams.find(_.accession == COLLISION_ENERGY_ACC) match {
				case Some(cv) => cv.value.get.toDouble
				case None => 
					val defCE = params.defaultCollisionEnergy.value
					if (defCE == 0.0)
						throw new Exception("No collision energy found in spectrum '%s'".format(s.id))
					else
						defCE
			}
			
		(ft, ce)
	}
	
	val demixOrigRE = """ORIG precInt=([^ ]+) rtInSec=([^ ]+) scan=(\d+) precRank=(\d+)""".r
	val demixFeatRE = """FEAT precInt=([^ ]+) rtInSec=([^ ]+) featApexInt=([^ ]+) scan=(\d+) precRank=(\d+)""".r
	val demixComplFragRE = """COMPL_FRAG scan=(\d+) precRank=(\d+)""".r
	val scanNumRE = """scan=(\d+)""".r.unanchored
	val absciexRE = """sample=(\d+) period=(\d+) cycle=(\d+) experiment=(\d+)""".r
	def parseSpectrumID(specIndex:Int, id:String) = 
		id match {
			case demixOrigRE(pInt, rt, scan, pRank) => DemixOrigID(scan.toInt, pInt.toDouble, rt.toDouble, pRank.toInt)
			case demixFeatRE(pInt, rt, fApexInt, scan, pRank) => DemixFeatID(scan.toInt, pInt.toDouble, rt.toDouble, fApexInt.toDouble, pRank.toInt)
			case demixComplFragRE(scan, pRank) => DemixComplFragID(scan.toInt, pRank.toInt)
			case scanNumRE(scan) => ScanID(scan.toInt, id)
			case absciexRE(sample, period, cycle, experiment) => AbSciexID(specIndex+1, sample.toInt, period.toInt, cycle.toInt, experiment.toInt)
			case _ => IndexID(specIndex+1, id)
		}
	
	
	def getReader(f:File):XmlReader = {
		val name = f.getName
		if (name.toLowerCase.endsWith(".mzml.gz"))
			new XmlReader(new BufferedReader(new InputStreamReader(
							new GZIPInputStream(new FileInputStream(f)))))
		else if (name.toLowerCase.endsWith(".mzml"))
			new XmlReader(new BufferedReader(new FileReader(f)))
		else
			throw new Exception("Unknown file format '%s'".format(name))
	}
	
	
	def guessFormat(path:String):IDs = 
		if (path.endsWith(".pep.csv") || path.endsWith(".pep.tsv")) PepCsv
		else if (path.endsWith(".xl.tsv") || path.endsWith(".xl.tsv")) Kojak
		else if (path.endsWith(".tsv") || path.endsWith(".csv")) MSGF
		else throw new Exception("""Unknown file format for path '%s'!
use
	.pep.csv or .pep.tsv 	for PeptideProphet derived IDs
	.xl.csv or .xl.tsv		for Kojak derived master XL format files
	.csv or .tsv			for MS-GF+ (demix) derived IDs""".format(path))
}