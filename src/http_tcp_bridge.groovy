#!/usr/bin/env groovy
// $Id: http_tcp_bridge.groovy,v 1.8 2016/12/28 22:59:58 greg Exp $
// GROOVY_TODO:
// how handle exceptions thrown at top level?
// how handle global variables methods and calling them from inside a class

// See https://docs.google.com/document/d/11XkNF9dxxQpXvpSuFtKDQBq_FjzFFBiqi3PW3WUrIxc/edit
// for details on the design of this service.

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

import groovy.json.JsonBuilder
import org.codehaus.groovy.runtime.StackTraceUtils

import java.nio.charset.StandardCharsets

def cli = new CliBuilder(usage: 'httpd-tcp-bridge.groovy')
cli.h('Help. Display this usage information')
cli.v('Verbose mode')
cli.H('Default to text/html responses')
cli.p(args:1, argName:'PORT', 'Use PORT for the HTTP service (defaults to 7316)')
cli.U('Default to url-unescaping the TCP response')
cli.s('Default to doing a single socket send (rather than splitting into linefeed-separated sends)')
cli.L('Add a linefeed (ascii 10) to each line sent to the socket')
cli.r('Add a carriage return (ascii 13) to each line sent to the socket')
cli.C(args:1, argName:'CONFIG_FILE', 'Read ip/port options from CONFIG_FILE, defaults to $HOME/.httpd-tcp-bridge')
cli.R(args:1, argName: 'REMOTE_SERVER:PORT', 'Use ssh to listen on REMOTE_SERVER:PORT for HTTP requests, too')
cli.P('Promiscuous mode: allow addressing any IP address even if not configured in the -C config file')

def opt = cli.parse(args)

if (opt.h) {
    cli.usage();
    return 0;
}

def port = opt.p ?: 7316

def config_options = []
def host_aliases = new LinkedHashMap()
def data_aliases = new LinkedHashMap()
def config_file = opt.C ?: System.getenv('HOME') + '/.httpd-tcp-bridge'

println "Listening on port $port, reading $config_file"
def stage = 0  // 0 = regex->options; 1 = hostalias -> hostport; 2 = datalias -> data
new File(config_file).eachLine {
  it.replace(/\#.*$/, "")
  if (it ==~ /\s*\s*/) {
    ++stage; return
  }
  if (it ==~ /\s*/) { return }
  if (0 == stage) {
    (regex, options) = it.split(/\t/,2)
    optlist = options.split(/[\t, ]/)
    co = [regex: regex, options: optlist ]
    println optlist[0]
    config_options << co
    println co
  } else if (1 == stage) {
    (hostalias, hostport) = it.split(/\s+/,2)
    host_aliases[hostalias] = hostport
  } else if (2 == stage) {
    (alias, data) = it.split(/\s+/,2)
    data_aliases[alias] = data
  } else {
    println "Error in config file; too many page breaks"
  }
}

if (opt.R) {
  def (host,rport) = opt.R.split(/:/)
  def cmd = "ssh -R $rport:127.0.0.1:$port $host &"
  cmd.execute()
  println "Forwarding from ${opt.R}..."
}


HttpServer server = HttpServer.create(new InetSocketAddress(port),0)
server.createContext("/quit", new QuitHandler(server:server, opt:opt))
server.createContext("/favicon.ico", new StaticHandler(server:server, opt:opt,
                      type:'image/x-icon', file:'http_tcp_bridge-favicon.ico'))
server.createContext("/", new BridgeHandler(server:server,
                                            opt:opt,
                                            config: config_options,
                                            host_aliases: host_aliases,
                                            data_aliases: data_aliases))
server.setExecutor(null); // creates a default executor
server.start();

class StaticHandler implements HttpHandler {
  def server
  def opt
  def type
  def file
 
  StaticHandler(Map m) {
    server = m.server
    opt = m.opt
    type = m.type
    file = m.file
  }

  public void handle(HttpExchange exchange) throws IOException {
    try {
      def f = new File(file)
      exchange.responseHeaders['Content-Type'] = type
      def response = f.bytes
      exchange.sendResponseHeaders(200, f.length())
      exchange.getResponseBody().write(response)
    } catch(e) {
      StackTraceUtils.sanitize(new Exception(e)).printStackTrace()
      println e
      response = "Not found"
      exchange.sendResponseHeaders(400, response.length())
      exchange.getResponseBody().write(response.bytes)
    } finally {
      File.close()
    }
  }
}

class BridgeHandler implements HttpHandler {
  def server
  def params
  def opt
  def config
  def host_aliases, data_aliases
  def def_want_html, def_want_uridecode, def_want_singlesend, def_addlf, def_addcr
  
  BridgeHandler(Map m) {
    server = m.server
    config = m.config
    host_aliases = m.host_aliases
    data_aliases = m.data_aliases
    opt = m.opt
    def_want_html = opt.H
    def_want_uridecode = opt.U
    def_want_singlesend = opt.s
    def_addlf = opt.L
    def_addcr = opt.r
  }
 
  public static Map query_as_map( String query ) {
    if (query != null) {
      query.split(/&/).inject([:]) {
        map, kv ->
        def (k,v) = kv.split(/=/).toList(); map[k] = v != null? URLDecoder.decode(v,"UTF-8"): null; map
      }
    } else {
      [:]
    }
  }

  String html_escape(String h ) {
    def answer = h.replaceAll(/&/, '&amp;')
    answer = answer.replaceAll(/</, '&lt;')
    return answer
  }

  public boolean bool_param( name, default_value) {
    def p = params[name]
    if (p != null && p ==~ /(?i)0|false|off|no/) {
      return false
    } else if (p != null && p ==~ /(?i)1|true|on|yes/) {
      return true
    } else {
      return default_value
    }
  }

  String doTCPSend(String dest, String data, boolean want_singlesend, boolean want_uridecode, boolean want_html,
                   boolean addlf, boolean addcr) {
    def (host, port) = dest.split(':')
    def s = new Socket(host, port.toInteger())
    s.setSoTimeout(5000)
    def data_lines = [data]
    if (!want_singlesend) {
      data_lines = data.split(/\n/)
    }
    def full_response = ""
    for (d in data_lines) {
      if (addlf) {
        d += "\n"
      }
      if (addcr) {
        d += "\r"
      }
      s.outputStream.write(d.bytes)
      println " Output $d to socket $s"
      def buffer = new byte[4096]
      try {
        def len = s.inputStream.read(buffer, 0, buffer.size())
        if (len == 0) {
          println "got len == 0"
          continue
        }
        def response = new String(buffer, 0, len, StandardCharsets.UTF_8)
        println "read $response from socket"
        if (want_uridecode) {
          response = URLDecoder.decode(response, "UTF-8")
        }
        if (want_html) {
          def request = html_escape(d)
          full_response += "<p class='request'>SENT: $request</p>"
          response = html_escape(response)
          full_response += "<p class='response'>$response</p>"
        } else {
          full_response += response
        }
      } catch (SocketTimeoutException e) {
        s.close()
        return "socket timeout " + e;
      }
    }
    if (want_html) {
      full_response = "<html><body>$full_response</body></html>"
    }
    s.close()
    return full_response
  }

  public void handle(HttpExchange exchange) throws IOException {
    def requestMethod = exchange.requestMethod

    def response = ""
    def response_type = 'text/plain'
    def response_code = 404

    try {
      def found_match = false
      params = query_as_map(exchange.requestURI.query)

      def want_html = bool_param('html', def_want_html)
      def want_uridecode = bool_param('uridecode', def_want_uridecode)
      def want_singlesend = bool_param('singlesend', def_want_singlesend)
      def addlf = bool_param('addlf', def_addlf)
      def addcr = bool_param('addcr', def_addcr)

      def uri = exchange.requestURI.path
      println "orig_uri = $uri"
      uri = uri.replaceAll(/^\/d\/(\w+)\/(.*)/, { full, m, d -> "/" + host_aliases.get(m,m) +"/" + data_aliases.get(d,d) })
      uri = uri.replaceAll(/^\/D\/(\w+)\//, { full, m -> "/" + host_aliases.get(m,m) + "/" })
      println "uri = $uri"

      if (uri =~ /\/.*?\//) {
        def (_, dest, data) = uri.split('/', 3)
        outer:
        for (def c in config) {
          if (dest =~ c.regex) {
            found_match = true
            println "found_match on ${c.regex}"
            for (def o in c.options) {
              def matcher = o =~ /^(?i)(!?)(html|uridecode|singlesend|addlf|addcr|forcedone)$/
              if (matcher.matches()) {
                def ov = matcher[0][2]
                def val = !(matcher[0][1] == '!')
                switch (ov) {
                  case 'html': want_html = val; break
                  case 'uridecode': want_uridecode = val; break
                  case 'singlesend': want_singlesend = val; break
                  case 'addlf': addlf = val; break
                  case 'addcr': addcr = val; break
                  case 'stop': break outer
                }
              }
            }
          }
        }
        if (!found_match && !opt.P) {
          response = "Destination of $dest is not allowed"  // unless -P promiscous mode
          return
        }
        data = URLDecoder.decode(data, "UTF-8")
        def debug = bool_param('debug', false)
        if (debug) {
          response = "html = $want_html, uridecode = $want_uridecode, singlesend = $want_singlesend, addlf = $addlf, addcr = $addcr: dest = $dest, data = $data"
        }
        response += doTCPSend(dest, data, want_singlesend, want_uridecode, want_html, addlf, addcr)

        response_type = want_html ? 'text/html' : 'text/plain'
        response_code = 200
      } else {
        response = "Ill-formed request"
        }
    } catch (e) {
      StackTraceUtils.sanitize(new Exception(e)).printStackTrace()
      println e
    } finally {
      exchange.responseHeaders['Content-Type'] = response_type
      exchange.sendResponseHeaders(response_code, response.length())
      exchange.getResponseBody().write(response.bytes)
      exchange.close()
    }      
  }
}
 
class QuitHandler implements HttpHandler {
  def server
  def opt

  QuitHandler(Map m) {
    server = m.server
    opt = m.opt
  }
  
  public void handle(HttpExchange exchange) throws IOException {
    exchange.responseHeaders['Content-Type'] = 'application/json'
    def builder = new JsonBuilder()
    builder {
      success true
      msg "Killing server..."
    }
    def response = builder.toString()
    exchange.sendResponseHeaders(200, response.length());
    exchange.getResponseBody().write(response.bytes);
    exchange.close();
    server.stop(3) //max wait 3 second
  }
}
