package edu.cmu.cs.ls.keymaera.hydra

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import edu.cmu.cs.ls.keymaera.api.ComponentConfig
import spray.can.Http

object Boot extends App {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("on-spray-can")

  // create and start our service actor
  val service = system.actorOf(Props[RestApiActor], "hydra")

  // spawn dependency injection framework
  ComponentConfig.keymaeraInitializer.initialize()

  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ! Http.Bind(service, interface = "localhost", port = 8090)
}
