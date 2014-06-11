package com.netaporter.uri.parsing

import org.parboiled2._
import com.netaporter.uri._
import com.netaporter.uri.config.UriConfig
import Parameters._
import scala.Predef._
import scala.Some

class UriParser(val input: ParserInput, conf: UriConfig) extends Parser {

  // -------------------------------------
  // Define CharPredicates as specified in
  //  - http://tools.ietf.org/html/rfc3986

  val _unreserved = CharPredicate.AlphaNum ++ "-._~"
  val _subDelim = "!$&'()*+,;="
  val _pctEncoded = '%'

  val _regName = _unreserved ++ _pctEncoded ++ _subDelim
  val _ipv4 = CharPredicate.Digit ++ '.'
  val _ipv6 = CharPredicate.HexDigit ++ ':'

  val _pchar = _unreserved ++ _pctEncoded ++ _subDelim ++ ':' ++ '@'

  val _userInfoChars = _unreserved ++ _pctEncoded ++ _subDelim ++ ':'
  val _hostChars = _regName ++ _ipv4 ++ _ipv6
  val _queryChars = _pchar ++ '/' ++ '?' -- '&'
  val _matrixParamChars = _pchar -- '=' -- ';'
  val _fragmentChars = _pchar ++ '/' ++ '?'

  // -------------------------------------
  // Define parser gammar

  def _scheme: Rule1[String] = rule {
    capture(oneOrMore(CharPredicate.AlphaNum))
  }

  def _host_name: Rule1[String] = rule {
    capture(oneOrMore(_hostChars))
  }

  def _userInfo: Rule1[UserInfo] = rule {
    capture(oneOrMore(_userInfoChars)) ~ optional(":" ~ capture(oneOrMore(_userInfoChars))) ~ "@" ~> extractUserInfo
  }

  //TODO Try harder to make this a Rule1[Int] using ~> extractInt
  def _port: Rule1[String] = rule {
    ":" ~ capture(oneOrMore(CharPredicate.Digit))
  }

  def _authority: Rule1[Authority] = rule {
    optional(_userInfo) ~ _host_name ~ optional(_port) ~> extractAuthority
  }

  def _matrixParam: Rule1[Param] = rule {
    capture(zeroOrMore(_matrixParamChars)) ~ "=" ~ capture(zeroOrMore(_matrixParamChars)) ~> extractTuple
  }

  def _plainPathPart: Rule1[String] = rule {
    capture(zeroOrMore(_pchar))
  }

  def _pathSegment: Rule1[PathPart] = rule {
    _plainPathPart ~ optional(";") ~ zeroOrMore(_matrixParam).separatedBy(";") ~> extractPathPart
  }

  /**
   * A sequence of path parts that MUST start with a slash
   */
  def _abs_path: Rule1[Vector[PathPart]] = rule {
    zeroOrMore("/" ~ _pathSegment) ~> extractPathParts
  }

  /**
   * A sequence of path parts optionally starting with a slash
   */
  def _rel_path: Rule1[Vector[PathPart]] = rule {
    optional("/") ~ zeroOrMore(_pathSegment).separatedBy("/") ~> extractPathParts
  }

  def _queryParam: Rule1[Param] = rule {
    capture(oneOrMore(_queryChars)) ~ "=" ~ capture(zeroOrMore(_queryChars)) ~> extractTuple
  }

  def _queryTok: Rule1[Param] = rule {
    capture(oneOrMore(_queryChars)) ~> extractTok
  }

  def _queryString: Rule1[QueryString] = rule {
    optional("?") ~ zeroOrMore(_queryParam | _queryTok).separatedBy("&") ~> extractQueryString
  }

  def _fragment: Rule1[String] = rule {
    "#" ~ capture(zeroOrMore(_fragmentChars))
  }

  def _abs_uri: Rule1[Uri] = rule {
    _scheme ~ "://" ~ optional(_authority) ~ _abs_path ~ _queryString ~ optional(_fragment) ~> extractAbsUri
  }

  def _protocol_rel_uri: Rule1[Uri] = rule {
    "//" ~ optional(_authority) ~ _abs_path ~ _queryString ~ optional(_fragment) ~> extractProtocolRelUri
  }

  def _rel_uri: Rule1[Uri] = rule {
    _rel_path ~ _queryString ~ optional(_fragment) ~> extractRelUri
  }

  def _uri: Rule1[Uri] = rule {
    (_abs_uri | _protocol_rel_uri | _rel_uri) ~ EOI
  }

  val extractAbsUri = (scheme: String, authority: Option[Authority], pp: Seq[PathPart], qs: QueryString, f: Option[String]) =>
    extractUri (
      scheme = Some(scheme),
      authority = authority,
      pathParts = pp,
      query = qs,
      fragment = f
    )

  val extractProtocolRelUri = (authority: Option[Authority], pp: Seq[PathPart], qs: QueryString, f: Option[String]) =>
    extractUri (
      authority = authority,
      pathParts = pp,
      query = qs,
      fragment = f
    )

  val extractRelUri = (pp: Seq[PathPart], qs: QueryString, f: Option[String]) =>
    extractUri (
      pathParts = pp,
      query = qs,
      fragment = f
    )

  def extractUri(scheme: Option[String] = None,
                 authority: Option[Authority] = None,
                 pathParts: Seq[PathPart],
                 query: QueryString,
                 fragment: Option[String]) =
    new Uri(
      scheme = scheme,
      user = authority.flatMap(_.user),
      password = authority.flatMap(_.password),
      host = authority.map(_.host),
      port = authority.flatMap(_.port),
      pathParts = pathParts,
      query = query,
      fragment = fragment
    )

  val extractInt = (num: String) =>
    num.toInt

  val extractUserInfo = (user: String, pass: Option[String]) =>
    UserInfo(pathDecoder.decode(user), pass.map(pathDecoder.decode))

  val extractAuthority = (userInfo: Option[UserInfo], host: String, port: Option[String]) =>
    Authority(userInfo.map(_.user), userInfo.flatMap(_.pass), host, port.map(_.toInt))

  val extractFragment = (x: String) =>
    fragmentDecoder.decode(x)
  
  val extractQueryString = (tuples: ParamSeq) =>
    QueryString(tuples.toVector.map(queryDecoder.decodeTuple))

  val extractPathPart = (pathPart: String, matrixParams: ParamSeq) => {
    val decodedPathPart = pathDecoder.decode(pathPart)
    val decodedMatrixParams = matrixParams.map(pathDecoder.decodeTuple)
    PathPart(decodedPathPart, decodedMatrixParams.toVector)
  }

  val extractPathParts = (pp: Seq[PathPart]) =>
    pp.toVector

  val extractTuple = (k: String, v: String) =>
    k -> v

  val extractTok = (k: String) => k -> ""

  /**
   * Used to made parsing easier to follow
   */
  case class Authority(user: Option[String], password: Option[String], host: String, port: Option[Int])
  case class UserInfo(user: String, pass: Option[String])

  def pathDecoder = conf.pathDecoder
  def queryDecoder = conf.queryDecoder
  def fragmentDecoder = conf.fragmentDecoder
}

object UriParser {
  def parse(s: String, config: UriConfig) =
    new UriParser(s, config)._uri.run().get
}