# civil-ci [![Build Status](https://travis-ci.org/tombooth/civil-ci.png?branch=master)](https://travis-ci.org/tombooth/civil-ci)

A CI server designed around docker and simple versioned config.

## Usage

### Development

There is a Vagrantfile (mainly stolen from [docker](https://github.com/dotcloud/docker/blob/master/Vagrantfile)) that should setup a VM with everything needed to work on civil-ci.

```
$ vagrant up
$ vagrant ssh -- -L 6789:127.0.0.1:6789
$ cd /var/app
$ lein repl :headless :port 6789
```

You should now be able to attach to the REPL from your local machine and start playing around.

## License

Copyright © 2014 Tom Booth

Distributed under the Eclipse Public License
