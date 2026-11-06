//| mvnDeps:
//| - org.scalameta:scalameta_2.13:4.17.4
//| - org.scala-lang.modules::scala-xml:2.5.0
//| scalacOptions: [-deprecation]
/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.nio.file.{ Files, Path, Paths }
import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.meta._

/**
 * CRAP scores for Scala methods: CRAP = CC² × (1 − coverage)³ + CC.
 *
 * Cyclomatic complexity comes from parsing the sources with scalameta, using the
 * decision points crapper uses for Java: if, while, do, for (and each guard),
 * each case of a match, partial function or catch (and each case guard), and
 * each && and ||.
 * Function literals count toward the enclosing method; nested methods, classes
 * and objects are scored on their own.
 *
 * Coverage is statement coverage from scoverage. Each statement is assigned to
 * the innermost method whose source range contains it.
 *
 * Usage, from the repository root:
 *   ./mill __.test && ./mill scoverage.xmlReportAll
 *   ./mill scripts/crap.scala [--threshold 10] [--changed] [--coverage <scoverage.xml>]
 *                             [--edn <path>] [--top N] [filter ...]
 *
 * Writes .metrics/crap-scala.edn and prints a report sorted worst first.
 * With --threshold N, fails (Mill exits 1) when the worst CRAP score is above N.
 */
object CrapScala {

  case class Method(
      name: String,
      namespace: String,
      file: String,
      start: Int,
      end: Int,
      complexity: Int)

  case class Entry(method: Method, coverage: Option[Double]) {
    val crap: Option[Double] = coverage.map(pct => {
      val cc = method.complexity.toDouble
      cc * cc * math.pow(1.0 - pct / 100.0, 3) + cc
    })
  }

  case class Statement(start: Int, invoked: Boolean)

  case class Options(
      root: Path = Paths.get("").toAbsolutePath,
      coverage: Option[Path] = None,
      edn: Option[Path] = None,
      threshold: Option[Double] = None,
      changed: Boolean = false,
      top: Option[Int] = None,
      filters: Seq[String] = Seq.empty)

  @annotation.tailrec
  private def parseArgs(args: List[String], opts: Options = Options()): Options = args match {
    case Nil                        => opts
    case "--root" :: p :: rest      => parseArgs(rest, opts.copy(root = Paths.get(p).toAbsolutePath))
    case "--coverage" :: p :: rest  => parseArgs(rest, opts.copy(coverage = Some(Paths.get(p))))
    case "--edn" :: p :: rest       => parseArgs(rest, opts.copy(edn = Some(Paths.get(p))))
    case "--threshold" :: n :: rest => parseArgs(rest, opts.copy(threshold = Some(n.toDouble)))
    case "--top" :: n :: rest       => parseArgs(rest, opts.copy(top = Some(n.toInt)))
    case "--changed" :: rest        => parseArgs(rest, opts.copy(changed = true))
    case f :: rest                  => parseArgs(rest, opts.copy(filters = opts.filters :+ f))
  }

  // ---- sources ----------------------------------------------------------

  private def isMainSource(p: Path, root: Path): Boolean = {
    val rel = root.relativize(p).toString
    rel.endsWith(".scala") && rel.contains("/src/main/scala/") && !rel.startsWith("out/")
  }

  private def changedFiles(root: Path): Set[Path] = {
    val out = os.proc("git", "status", "--porcelain").call(cwd = os.Path(root)).out.lines()
    out.flatMap(line => line.drop(3).split(" -> ").lastOption)
      .map(f => root.resolve(f).normalize)
      .toSet
  }

  private def sources(opts: Options): Seq[Path] = {
    val all = Files.walk(opts.root).iterator().asScala
      .filter(p => Files.isRegularFile(p) && isMainSource(p, opts.root))
      .toSeq
    val changedOnly = if (opts.changed) {
      val changed = changedFiles(opts.root)
      all.filter(changed.contains)
    } else all
    val filtered = if (opts.filters.isEmpty) changedOnly
    else changedOnly.filter(p => opts.filters.exists(f => p.toString.contains(f)))
    filtered.sorted
  }

  // ---- complexity -------------------------------------------------------

  /** Decision points in a tree, not descending into nested methods or types. */
  private def decisions(tree: Tree): Int = {
    val here = tree match {
      case _: Term.If                        => 1
      case _: Term.While                     => 1
      case _: Term.Do                        => 1
      case _: Term.For                       => 1
      case _: Term.ForYield                  => 1
      case _: Enumerator.Guard               => 1
      case c: Case                           => if (c.cond.isDefined) 2 else 1
      case t: Term.ApplyInfix if t.op.value == "&&" || t.op.value == "||" => 1
      case _                                 => 0
    }
    here + tree.children.map {
      case _: Defn.Def | _: Defn.Class | _: Defn.Object | _: Defn.Trait | _: Ctor.Secondary => 0
      case child => decisions(child)
    }.sum
  }

  private def methods(file: Path): Seq[Method] = {
    val text = new String(Files.readAllBytes(file), "UTF-8")
    dialects.Scala213(Input.VirtualFile(file.toString, text)).parse[Source] match {
      case err: Parsed.Error =>
        System.err.println(s"skipping ${file}: ${err.message}")
        Seq.empty
      case Parsed.Success(source) =>
        val found = Seq.newBuilder[Method]

        def visit(tree: Tree, namespace: String, enclosingDef: Option[String]): Unit = tree match {
          case p: Pkg =>
            val ns = if (namespace.isEmpty) p.ref.syntax else s"$namespace.${p.ref.syntax}"
            p.body.stats.foreach(visit(_, ns, None))
          case t: Defn.Class  => visitType(t.name.value, t.templ, namespace)
          case t: Defn.Trait  => visitType(t.name.value, t.templ, namespace)
          case t: Defn.Object => visitType(t.name.value, t.templ, namespace)
          case d: Defn.Def =>
            val name = enclosingDef.fold(d.name.value)(outer => s"$outer.${d.name.value}")
            found += Method(name, namespace, file.toString, d.pos.start, d.pos.end, 1 + decisions(d.body))
            // nested methods and types inside the body are scored on their own
            visit(d.body, namespace, Some(name))
          case c: Ctor.Secondary =>
            found += Method("this", namespace, file.toString, c.pos.start, c.pos.end, 1 + decisions(c))
          case other =>
            other.children.foreach(visit(_, namespace, enclosingDef))
        }

        def visitType(name: String, templ: Template, namespace: String): Unit = {
          val ns = if (namespace.isEmpty) name else s"$namespace.$name"
          templ.children.foreach(visit(_, ns, None))
        }

        visit(source, "", None)
        found.result().distinctBy(m => (m.start, m.end, m.name))
    }
  }

  // ---- coverage ---------------------------------------------------------

  private def statements(report: Path): Map[String, Seq[Statement]] = {
    val xml = scala.xml.XML.loadFile(report.toFile)
    (xml \\ "statement")
      .filter(s => (s \@ "ignored") != "true")
      .map(s => (s \@ "source") -> Statement((s \@ "start").toInt, (s \@ "invocation-count").toInt > 0))
      .groupBy(_._1)
      .map { case (source, pairs) => Paths.get(source).normalize.toString -> pairs.map(_._2) }
  }

  /** Statement coverage per method, each statement counted in its innermost enclosing method. */
  private def coverage(ms: Seq[Method], stmts: Map[String, Seq[Statement]]): Map[Method, Option[Double]] = {
    ms.groupBy(_.file).flatMap {
      case (file, fileMethods) =>
        val counts = scala.collection.mutable.Map.empty[Method, (Int, Int)].withDefaultValue((0, 0))
        stmts.getOrElse(Paths.get(file).normalize.toString, Seq.empty).foreach(st => {
          val enclosing = fileMethods.filter(m => m.start <= st.start && st.start < m.end)
          if (enclosing.nonEmpty) {
            val m = enclosing.minBy(m => m.end - m.start)
            val (total, hit) = counts(m)
            counts(m) = (total + 1, hit + (if (st.invoked) 1 else 0))
          }
        })
        val hasReport = stmts.contains(Paths.get(file).normalize.toString)
        fileMethods.map(m => {
          val (total, hit) = counts(m)
          // a method without statements (e.g. returning a constant) is fully covered
          // if its file was instrumented; without a report its coverage is unknown
          m -> (if (!hasReport) None else if (total == 0) Some(100.0) else Some(100.0 * hit / total))
        })
    }
  }

  // ---- output -----------------------------------------------------------

  private def report(entries: Seq[Entry]): String = {
    val nameWidth = math.max(30, math.min(48, if (entries.isEmpty) 0 else entries.map(_.method.name.length).max))
    val nsWidth = math.max(35, math.min(72, if (entries.isEmpty) 0 else entries.map(_.method.namespace.length).max))
    val header = s"%-${nameWidth}s %-${nsWidth}s %4s %7s %8s".format("Function", "Namespace", "CC", "Cov%", "CRAP")
    val rows = entries.map(e =>
      s"%-${nameWidth}s %-${nsWidth}s %4d %7s %8s".format(
        e.method.name, e.method.namespace, e.method.complexity,
        e.coverage.fold("  N/A ")(c => "%5.1f%%".formatLocal(Locale.ROOT, c)),
        e.crap.fold("     N/A")(c => "%8.1f".formatLocal(Locale.ROOT, c))))
    (Seq("CRAP Report", "===========", "Languages: scala", "", header, "-" * header.length) ++ rows).mkString("\n")
  }

  private def ednString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def edn(entries: Seq[Entry]): String = {
    def num(d: Option[Double]) = d.fold("nil")(v => BigDecimal(v).setScale(2, BigDecimal.RoundingMode.HALF_UP).toString)
    entries.map(e =>
      s"  {:name ${ednString(e.method.name)}, :namespace ${ednString(e.method.namespace)}, " +
        s":complexity ${e.method.complexity}, :coverage ${num(e.coverage)}, :crap ${num(e.crap)}}")
      .mkString("{:entries [\n", "\n", "\n]}\n")
  }

  def main(args: Array[String]): Unit = {
    val opts = parseArgs(args.toList)
    val coveragePath = opts.coverage.getOrElse(opts.root.resolve("out/scoverage/xmlReportAll.dest/scoverage.xml"))
    val stmts = if (Files.exists(coveragePath)) statements(coveragePath) else {
      System.err.println(s"no coverage report at $coveragePath; run ./mill __.test && ./mill scoverage.xmlReportAll. Scoring complexity only.")
      Map.empty[String, Seq[Statement]]
    }

    val ms = sources(opts).flatMap(methods)
    val cov = coverage(ms, stmts)
    val entries = ms.map(m => Entry(m, cov(m)))
      .sortBy(e => (-e.crap.getOrElse(-1.0), -e.method.complexity, e.method.namespace, e.method.name))

    val shown = opts.top.fold(entries)(n => entries.take(n))
    println(report(shown))

    val ednPath = opts.edn.getOrElse(opts.root.resolve(".metrics/crap-scala.edn"))
    Files.createDirectories(ednPath.getParent)
    Files.write(ednPath, edn(entries).getBytes("UTF-8"))

    val over10 = entries.count(_.crap.exists(_ >= 10.0))
    println()
    println(s"Scored ${entries.size} methods in ${ms.map(_.file).distinct.size} files; ${over10} at or above CRAP 10.")
    println(s"Wrote $ednPath")

    opts.threshold.foreach(t => {
      val worst = entries.flatMap(_.crap).maxOption.getOrElse(0.0)
      if (worst > t) sys.exit(2)
    })
  }
}
