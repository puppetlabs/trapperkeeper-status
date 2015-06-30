# status-proxy-service

## Use Case
The `status-proxy-service` acts as a bridge between HTTP clients and an HTTPS
`status-service`. It is configured with SSL information about a 
`status-service`, and then accepts HTTP requests and proxies them over HTTPS 
to the real `status-service`. 

This could be useful for instance, if you have a load balancer that needs to
access the `status-service`, but which cannot be configured to use custom SSL
certs from pem files.

## Preferred Alternatives
Given that the `status-proxy-service` opens a small hole in your security,
consider the following alternatives first:

* Configure your load balancer to use HTTPS with pem files if possible
* If you are comfortable setting up reverse proxying with a tool such as nginx,
  consider using that
* Consider running the regular status service on a plaintext port, so that only
  the status service is accessible via plaintext, and you are not reverse
  proxying any requests to the HTTPS portions of the apps

## Security Considerations
There is some security risk associated with providing a plaintext window into
a portion of your HTTPS endpoints.
  
* If incorrectly configured, the `status-proxy-service` could unintentionally
  open up endpoints other than the `status-service` to plaintext access
* The proxy should be run on a network interface that is not accessible from
  outside your internal LAN if possible
* The proxy should be run on a port that is firewalled to only allow access
  from your load balancer

## Examples

### Configuring the status-service for plaintext access

If you would like to configure your status service to be on a plaintext port,
your trapperkeeper configuration might look something like this:

```
webserver: {
  default: {
    ssl-port: 9001
    ssl-cert: /etc/ssl/certs/myhostname.pem
    ssl-key: /etc/ssl/private_keys/myhostname.pem
    ssl-ca-cert: /etc/ssl/certs/ca.pem
    default-server: true
  }

  status: {
    port: 8080
  }
}

web-router-service: {
  "puppetlabs.trapperkeeper.services.status.status-service/status-service": {
    route: /status
    server: status
  }
}
```

This config contains a new jetty server called `status` running on port 8080
that is separate from the other, SSL enabled, server. In the 
`web-router-service` section, the status service is configured to run on the
`status` server, and be mounted at `/status`


### Configuring the status-proxy-service

#### bootstrap.cfg
You'll need to add the `status-proxy-service` to your `bootstrap.cfg`:
```
puppetlabs.trapperkeeper.services.status.status-proxy-service/status-proxy-service
```

#### Trapperkeeper config
Your trapperkeeper configuration might look like this:

```
webserver: {
  default: {
    ssl-port: 9001
    ssl-cert: /etc/ssl/certs/myhostname.pem
    ssl-key: /etc/ssl/private_keys/myhostname.pem
    ssl-ca-cert: /etc/ssl/certs/ca.pem
    default-server: true
  }

  status-proxy: {
    port: 8080
  }
}

web-router-service: {
  "puppetlabs.trapperkeeper.services.status.status-service/status-service": /status

  "puppetlabs.trapperkeeper.services.status.status-proxy-service/status-proxy-service": {
    route: /status-proxy
    server: status-proxy
  }
}

status-proxy: {
  proxy-target-url: "https://myhostname:9001/status"
  ssl-opts: {
    ssl-cert: /etc/ssl/certs/myhostname.pem
    ssl-key: /etc/ssl/private_keys/myhostname.pem
    ssl-ca-cert: /etc/ssl/certs/ca.pem
  }
}
```

The important things to note are:
* The status proxy service and the status service are running on separate
  webservers and ports
* There is a new section in the config, `status-proxy`, with:
  * A url pointing the proxy to the status service. Note that he hostname of
    the url must be the CN or a SubjectAltName in the server certificate
  * SSL information that matches the SSL information for the webserver that the
    status service is running on

With this configuration, requests that would normally be made to
`https://myhostname:9001/status` with an SSL cert can also be made to
`http://myhostname:8080/status-proxy` over plaintext
