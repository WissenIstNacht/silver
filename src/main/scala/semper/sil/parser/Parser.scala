package semper.sil.parser

import org.kiama.util.WhitespacePositionedParserUtilities
import java.io.File
import java.nio.file.Path

/**
 * A parser for the SIL language that takes a string and produces an intermediate
 * AST ([[semper.sil.parser.PNode]]), or a parse error.  The intermediate AST can
 * then be type-checked and translated into the SIL AST ([[semper.sil.ast.Node]])
 * using [[semper.sil.parser.Translator]].
 *
 * IMPORTANT: If you change or extend the syntax, please also update the synatx
 * description in documentation/syntax as well as the syntax highlighting definitions
 * in util/highlighting!
 */
object Parser extends BaseParser {

  override def file = _file
  var _file: Path = null

  def parse(s: String, f: Path) = {
    _file = f
    val r = parseAll(parser, s)
    r match {
      // make sure the tree is correctly initialized
      case Success(e, _) => e.initTreeProperties()
      case _ =>
    }
    r
  }
}

trait BaseParser extends WhitespacePositionedParserUtilities {

  /** The file we are currently parsing (for creating positions later). */
  def file: Path

  /**
   * All keywords of SIL.
   *
   * IMPORTANT: If you add any new keywords, please also update all syntax highlighters
   * in util/highlighting.  Also update the SIL syntax description in documentation/syntax.
   */
  def reserved: List[String] = List(
    // special variables
    "result",
    // types
    "Int", "Perm", "Bool", "Ref",
    // boolean constants
    "true", "false",
    // null
    "null",
    // declaration keywords
    "method", "function", "predicate", "program", "domain", "axiom", "var", "returns",
    // specifications
    "requires", "ensures", "invariant",
    // statements
    "fold", "unfold", "inhale", "exhale", "new", "assert", "assume", "goto", "package", "apply",
    // control structures
    "while", "if", "elsif", "else",
    // special fresh block
    "fresh",
    // sequences
    "Seq",
    // sets and multisets
    "Set", "Multiset", "union", "intersection", "setminus", "subset",
    // unfolding expressions
    "unfolding", "in",
    // old expression
    "old",
    // quantification
    "forall", "exists",
    // permission syntax
    "acc", "wildcard", "write", "none", "epsilon", "perm",
    // modifiers
    "unique"
  )

  lazy val parser = phrase(programDecl)

  // --- Whitespace

  lazy val whitespaceParser: PackratParser[Any] =
    rep(whiteSpace | comment)

  lazy val comment: PackratParser[Any] =
    ("/*" ~ rep(not("*/") ~ (comment | any)) ~ "*/") |
      ("//" ~ rep(not("\n") ~ any))

  // --- Declarations

  lazy val programDecl =
    rep(domainDecl | fieldDecl | functionDecl | predicateDecl | methodDecl) ^^ {
      case decls =>
        val domains = decls collect {
          case d: PDomain => d
        }
        val fields = decls collect {
          case d: PField => d
        }
        val functions = decls collect {
          case d: PFunction => d
        }
        val predicates = decls collect {
          case d: PPredicate => d
        }
        val methods = decls collect {
          case d: PMethod => d
        }
        PProgram(file, domains, fields, functions, predicates, methods)
    }

  lazy val fieldDecl =
    ("var" ~> idndef) ~ (":" ~> typ) ^^ PField

  lazy val methodDecl =
    methodSignature ~ rep(pre) ~ rep(post) ~ block ^^ {
      case name ~ args ~ rets ~ pres ~ posts ~ body =>
        PMethod(name, args, rets.getOrElse(Nil), pres, posts, PSeqn(body))
    }
  lazy val methodSignature =
    ("method" ~> idndef) ~ ("(" ~> formalArgList <~ ")") ~ opt("returns" ~> ("(" ~> formalArgList <~ ")"))
  lazy val pre =
    "requires" ~> exp <~ opt(";")
  lazy val post =
    "ensures" ~> exp <~ opt(";")
  lazy val inv =
    "invariant" ~> exp <~ opt(";")

  lazy val formalArgList =
    repsep(formalArg, ",")
  lazy val formalArg =
    idndef ~ (":" ~> typ) ^^ PFormalArgDecl

  lazy val functionDecl =
    functionSignature ~ rep(pre) ~ rep(post) ~ ("{" ~> (exp <~ "}")) ^^ PFunction
  lazy val functionSignature =
    ("function" ~> idndef) ~ ("(" ~> formalArgList <~ ")") ~ (":" ~> typ)

  lazy val domainFunctionDecl = opt("unique") ~ (functionSignature <~ opt(";")) ^^ {
    case unique ~ fdecl =>
      fdecl match {
        case name ~ formalArgs ~ t => PDomainFunction(name, formalArgs, t, unique.isDefined)
      }
  }

  lazy val predicateDecl =
    ("predicate" ~> idndef) ~ ("(" ~> formalArgList <~ ")") ~ ("{" ~> (exp <~ "}")) ^^ PPredicate

  lazy val domainDecl =
    ("domain" ~> idndef) ~
      opt("[" ~> repsep(idndef, ",") <~ "]") ~
      ("{" ~> rep(domainFunctionDecl)) ~
      (rep(axiomDecl) <~ "}") ^^ {
      case name ~ typparams ~ funcs ~ axioms =>
        PDomain(name, typparams.getOrElse(Nil), funcs, axioms)
    }

  lazy val axiomDecl =
    ("axiom" ~> idndef) ~ ("{" ~> (exp <~ "}")) <~ opt(";") ^^ PAxiom

  // --- Statements

  def parens[A](p: Parser[A]) = "(" ~> p <~ ")"

  lazy val block: Parser[Seq[PStmt]] =
    "{" ~> (stmts <~ "}")
  lazy val stmts =
    rep(stmt <~ opt(";"))
  lazy val stmt =
    fieldassign | localassign | fold | unfold | exhale | assert |
      inhale | ifthnels | whle | varDecl | newstmt | freshReadPerm |
      methodCall | goto | lbl | packageWand | applyWand

  lazy val fold =
    "fold" ~> exp ^^ PFold
  lazy val unfold =
    "unfold" ~> exp ^^ PUnfold
  lazy val packageWand =
    "package" ~> exp ^^ PPackageWand
  lazy val applyWand =
    "apply" ~> exp ^^ PApplyWand
  lazy val inhale =
    ("inhale" | "assume") ~> (exp) ^^ PInhale
  lazy val exhale =
    "exhale" ~> (exp) ^^ PExhale
  lazy val assert =
    "assert" ~> (exp) ^^ PAssert
  lazy val localassign =
    idnuse ~ (":=" ~> exp) ^^ PVarAssign
  lazy val fieldassign =
    fieldAcc ~ (":=" ~> exp) ^^ PFieldAssign
  lazy val ifthnels =
    ("if" ~> "(" ~> exp <~ ")") ~ block ~ elsifEls ^^ {
      case cond ~ thn ~ els => PIf(cond, PSeqn(thn), els)
    }
  lazy val elsifEls: PackratParser[PStmt] = elsif | els
  lazy val elsif: PackratParser[PStmt] =
    ("elsif" ~> "(" ~> exp <~ ")") ~ block ~ elsifEls ^^ {
      case cond ~ thn ~ els => PIf(cond, PSeqn(thn), els)
    }
  lazy val els: PackratParser[PStmt] = opt("else" ~> block) ^^ { block => PSeqn(block.getOrElse(Nil)) }
  lazy val whle =
    ("while" ~> "(" ~> exp <~ ")") ~ rep(inv) ~ block ^^ {
      case cond ~ invs ~ body => PWhile(cond, invs, PSeqn(body))
    }
  lazy val varDecl =
    ("var" ~> idndef) ~ (":" ~> typ) ~ opt(":=" ~> exp) ^^ PLocalVarDecl
  lazy val freshReadPerm =
    ("fresh" ~> "(" ~> repsep(idnuse, ",") <~ ")") ~ block ^^ {
      case vars ~ s => PFreshReadPerm(vars, PSeqn(s))
    }
  lazy val newstmt =
    idnuse <~ (":=" ~ "new" ~ "()") ^^ PNewStmt
  lazy val lbl =
    idndef <~ ":" ^^ PLabel
  lazy val goto =
    "goto" ~> idnuse ^^ PGoto
  lazy val methodCall =
    opt(repsep(idnuse, ",") <~ ":=") ~ idnuse ~ parens(repsep(exp, ",")) ^^ {
      case None ~ method ~ args => PMethodCall(Nil, method, args)
      case Some(targets) ~ method ~ args => PMethodCall(targets, method, args)
    }

  // --- Types

  lazy val typ: PackratParser[PType] =
    primitiveTyp | domainTyp | seqType | setType | multisetType
  lazy val domainTyp: PackratParser[PDomainType] =
    idnuse ~ ("[" ~> (repsep(typ, ",") <~ "]")) ^^ PDomainType |
      idnuse ^^ {
        // domain type without type arguments (might also be a type variable)
        case name => PDomainType(name, Nil)
      }
  lazy val seqType: PackratParser[PType] =
    "Seq[" ~> typ <~ "]" ^^ PSeqType
  lazy val setType: PackratParser[PType] =
    "Set[" ~> typ <~ "]" ^^ PSetType
  lazy val multisetType: PackratParser[PType] =
    "Multiset[" ~> typ <~ "]" ^^ PMultisetType
  lazy val primitiveTyp: PackratParser[PType] =
    ("Int" | "Bool" | "Perm" | "Ref") ^^ PPrimitiv

  // --- Expressions

  lazy val exp: PackratParser[PExp] =
    iteExpr

  lazy val iteExpr: PackratParser[PExp] =
    ((iffExp <~ "?") ~ iteExpr ~ (":" ~> iteExpr)) ^^ PCondExp | iffExp
  lazy val iffExp: PackratParser[PExp] =
    implExp ~ "<==>" ~ iffExp ^^ PBinExp | implExp
  lazy val implExp: PackratParser[PExp] =
    magicWandExp ~ "==>" ~ implExp ^^ PBinExp | magicWandExp
  lazy val magicWandExp: PackratParser[PExp] =
    orExp ~ "--*" ~ magicWandExp ^^ PBinExp | orExp
  lazy val orExp: PackratParser[PExp] =
    andExp ~ "||" ~ orExp ^^ PBinExp | andExp
  lazy val andExp: PackratParser[PExp] =
    cmpExp ~ "&&" ~ andExp ^^ PBinExp | cmpExp

  lazy val cmpOp = "==" | "!=" | "<=" | ">=" | "<" | ">"
  lazy val cmpExp: PackratParser[PExp] =
    sum ~ cmpOp ~ sum ^^ PBinExp | sum

  lazy val sum: PackratParser[PExp] =
    sum ~ "++" ~ term ^^ PBinExp |
      sum ~ "+" ~ term ^^ PBinExp |
      sum ~ "-" ~ term ^^ PBinExp |
      term

  lazy val term: PackratParser[PExp] =
    term ~ "*" ~ factor ^^ PBinExp |
      term ~ "/" ~ factor ^^ PBinExp |
      term ~ "\\" ~ factor ^^ PBinExp |
      term ~ "%" ~ factor ^^ PBinExp |
      factor

  lazy val factor: PackratParser[PExp] =
    fapp |
      set |
      seq |
      locAcc |
      integer |
      bool |
      nul |
      idnuse |
      "result" ^^^ PResultLit() |
      ("-" | "!" | "+") ~ sum ^^ PUnExp |
      "(" ~> exp <~ ")" |
      accessPred |
      inhaleExhale |
      perm |
      quant |
      unfolding |
      old

  lazy val accessPred: PackratParser[PAccPred] =
    "acc" ~> parens(locAcc ~ ("," ~> exp)) ^^ PAccPred

  lazy val fapp: PackratParser[PExp] =
    idnuse ~ parens(actualArgList) ^^ PFunctApp
  lazy val actualArgList: PackratParser[Seq[PExp]] =
    repsep(exp, ",")

  lazy val inhaleExhale: PackratParser[PExp] = ("[" ~> exp <~ ",") ~ (exp <~ "]") ^^ PInhaleExhaleExp

  lazy val perm: PackratParser[PExp] =
    "none" ^^^ PNoPerm() | "wildcard" ^^^ PWildcard() | "write" ^^^ PFullPerm() |
      "epsilon" ^^^ PEpsilon() | "perm" ~> parens(locAcc) ^^ PCurPerm

  lazy val quant: PackratParser[PExp] =
    ("forall" ~> formalArgList <~ "::") ~ rep(trigger) ~ exp ^^ PForall |
      ("exists" ~> formalArgList <~ "::") ~ exp ^^ PExists
  lazy val trigger: PackratParser[Seq[PExp]] =
    "{" ~> repsep(exp, ",") <~ "}"

  lazy val old: PackratParser[PExp] =
    "old" ~> parens(exp) ^^ POld

  lazy val locAcc: PackratParser[PLocationAccess] =
    // the first two cases are a hack to get all/chalice/nestedPredicates to parse
    (("^3" ~> idnuse) ~ ("." ~> idnuse) ~ ("." ~> idnuse)) ^^ {
      case i1 ~ i2 ~ i3 => PFieldAccess(PFieldAccess(i1, i2), i3)
    } | (("^4" ~> idnuse) ~ ("." ~> idnuse) ~ ("." ~> idnuse) ~ ("." ~> idnuse <~ "()")) ^^ {
      case i1 ~ i2 ~ i3 ~ i4 => PPredicateAccess(Seq(PFieldAccess(PFieldAccess(i1, i2), i3)), i4)
    } | predAcc | fieldAcc
  lazy val fieldAcc: PackratParser[PFieldAccess] =
    (exp <~ ".") ~ idnuse ^^ PFieldAccess
  lazy val predAcc: PackratParser[PLocationAccess] =
    (exp <~ ".") ~ idnuse ~ parens(actualArgList) ^^ {
      case rcv ~ idn ~ args => PPredicateAccess(Seq(rcv) ++ args, idn)
    }

  lazy val unfolding: PackratParser[PExp] =
    ("unfolding" ~> accessPred) ~ ("in" ~> exp) ^^ PUnfolding

  lazy val integer =
    "[0-9]+".r ^^ (s => PIntLit(BigInt(s)))

  lazy val bool =
    "true" ^^^ PBoolLit(b = true) | "false" ^^^ PBoolLit(b = false)

  lazy val nul =
    "null" ^^^ PNullLit()

  lazy val idndef =
    ident ^^ PIdnDef

  lazy val idnuse =
    ident ^^ PIdnUse

  // --- Sequences

  lazy val seq =
    seqTypedEmpty | seqLength | explicitSeq | seqIndex | seqRange |
      seqTake | seqDrop | seqTakeDrop |
      seqContains | seqUpdate
  lazy val seqTypedEmpty: PackratParser[PExp] =
    "Seq[" ~> typ <~ "]()" ^^ PEmptySeq
  lazy val seqLength: PackratParser[PExp] =
    "|" ~> exp <~ "|" ^^ PSize
  lazy val seqTake: PackratParser[PExp] =
    exp ~ ("[.." ~> exp <~ "]") ^^ PSeqTake
  lazy val seqDrop: PackratParser[PExp] =
    exp ~ ("[" ~> exp <~ "..]") ^^ PSeqDrop
  lazy val seqTakeDrop: PackratParser[PExp] =
    exp ~ ("[" ~> exp <~ "..") ~ (exp <~ "]") ^^ {
      case s ~ drop ~ take => PSeqDrop(PSeqTake(s, take), drop)
    }
  lazy val seqIndex: PackratParser[PExp] =
    (exp <~ "[") ~ (exp <~ "]") ^^ PSeqIndex
  lazy val seqContains: PackratParser[PExp] =
    exp ~ "in" ~ exp ^^ PBinExp
  lazy val seqUpdate: PackratParser[PExp] =
    exp ~ ("[" ~> exp <~ ":=") ~ (exp <~ "]") ^^ PSeqUpdate
  lazy val explicitSeq: PackratParser[PExp] =
    "Seq(" ~> repsep(exp, ",") <~ ")" ^^ {
      case Nil => PEmptySeq(PUnknown())
      case elems => PExplicitSeq(elems)
    }
  lazy val seqRange: PackratParser[PExp] =
    ("[" ~> exp <~ "..") ~ (exp <~ ")") ^^ PRangeSeq

  // --- Sets

  lazy val set =
    setUnion | setIntersection | setMinus | subset |
      explicitSet | explicitMultiset
  lazy val setUnion: PackratParser[PExp] =
    exp ~ "union" ~ exp ^^ PBinExp
  lazy val setIntersection: PackratParser[PExp] =
    exp ~ "intersection" ~ exp ^^ PBinExp
  lazy val setMinus: PackratParser[PExp] =
    exp ~ "setminus" ~ exp ^^ PBinExp
  lazy val subset: PackratParser[PExp] =
    exp ~ "subset" ~ exp ^^ PBinExp
  lazy val explicitSet: PackratParser[PExp] =
    "Set(" ~> repsep(exp, ",") <~ ")" ^^ {
      case Nil => PEmptySet()
      case elems => PExplicitSet(elems)
    }
  lazy val explicitMultiset: PackratParser[PExp] =
    "Multiset(" ~> repsep(exp, ",") <~ ")" ^^ {
      case Nil => PEmptySet()
      case elems => PExplicitSet(elems)
    }

  // --- Identifier and keywords

  lazy val ident =
    not(keyword) ~> identifier.r |
      failure("identifier expected")

  lazy val identFirstLetter = "[a-zA-Z$_]"

  lazy val identOtherLetter = "[a-zA-Z0-9$_']"

  lazy val identifier = identFirstLetter + identOtherLetter + "*"

  lazy val keyword =
    keywords("[^a-zA-Z0-9]".r, reserved)
}
