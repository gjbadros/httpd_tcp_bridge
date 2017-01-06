# httpd_tcp_bridge

## Synopsis

httpd_tcp_bridge is a simple web server (an HTTP daemon) that acts as
a bridge to raw TCP command languages.  It listens for HTTP requests
(on port 7316 by default) and issues TCP commands on the local network
where the server runs.

## Description

See [HTTPD-TCP-Bridge.pdf](HTTPD-TCP-Bridge.pdf) for documentation, or run with -h for a usage message.

## Warnings

Without any configuration, this server allows access to the entire network that the server has access to.

Even with configuration, this server likely has bugs that permit that same full access including possibly escalations to superuser privileges.


## Author

Greg Badros <badros@gmail.com>

## License

GPL

You use this completely at your own risk and I accept no liability for anything that happens due to your use of this software.

