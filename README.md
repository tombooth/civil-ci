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

## API design

An outline of what the endpoints will look like and the functionality they should have

```

GET  /jobs
     Lists of jobs
POST /jobs
     Adds a job
GET  /jobs/:id
     Gets a job by id
PUT  /jobs/:id
     Updates a job
POST /jobs/:id/run
     Synonym for /jobs/:id/build/run

GET  /jobs/:id/workspace/steps
     Get all of the steps to create a workspace
POST /jobs/:id/workspace/steps
     Add a step
PUT  /jobs/:id/workspace/steps/:ordinal
     Change a step
GET  /jobs/:id/workspace/run
     Get the history of runs for this workspace
POST /jobs/:id/workspace/run
     Executes the steps inside of a docker image, tagging it for use with build.
     Returns an id for the run
GET  /jobs/:id/workspace/run/:id
     Gets the history of one particular run

GET  /jobs/:id/build/steps
     Get a list of all the steps in the build
POST /jobs/:id/build/steps
     Add a step to the build
PUT  /jobs/:id/build/steps/:ordinal
     Update a step in the build
GET  /jobs/:id/build/run
     List the history of runs for this build
POST /jobs/:id/build/run
     Runs all the steps inside of a docker container using the latest tagged workspace.
     Falls back to ubuntu base image if there is no tagged workspace.
GET  /jobs/:id/build/run/:id
     Returns the history of one particular run

```

## License

Copyright © 2014 Tom Booth

Distributed under the Eclipse Public License
