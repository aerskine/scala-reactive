package org.deler.events

import org.joda.time._
import org.junit.runner.RunWith
import org.specs._
import org.specs.mock.Mockito
import org.specs.runner.{ JUnitSuiteRunner, JUnit }
import org.mockito.Matchers._
import scala.collection._

@RunWith(classOf[JUnitSuiteRunner])
class SchedulingTest extends Specification with JUnit with Mockito {

  val INITIAL = new Instant

  var subject = new VirtualScheduler(INITIAL)

  var count = 0
  def action(expectedTime: Instant = INITIAL) { subject.now must be equalTo expectedTime; count += 1 }

  "virtual schedule" should {
    "not run an action when it is scheduled" in {
      subject schedule action()

      count must be equalTo 0
    }
    "run scheduled action" in {
      subject schedule action()

      subject.run

      count must be equalTo 1
    }
    "run scheduled action at specified time" in {
      subject.scheduleAfter(new Duration(1000L)) { action(INITIAL.plus(1000)) }

      subject.run

      subject.now must be equalTo INITIAL.plus(1000)
    }
    "never take the clock backwards" in {
      subject.scheduleAt(INITIAL minus 1000) { action(INITIAL) }

      subject.run

      subject.now must be equalTo INITIAL
    }
    "run actions in scheduled ordered" in {
      subject.scheduleAfter(new Duration(2000L)) { action(INITIAL.plus(2000)) }
      subject.scheduleAfter(new Duration(1000L)) { action(INITIAL.plus(1000)) }

      subject.run

      count must be equalTo 2
      subject.now must be equalTo INITIAL.plus(2000)
    }
    "run actions that are scheduled by other actions" in {
      subject schedule {
        subject.scheduleAfter(new Duration(1000L)) { action(INITIAL.plus(1000)) }
      }

      subject.run

      count must be equalTo 1
      subject.now must be equalTo INITIAL.plus(1000)
    }
    "run actions upto the specified instant (inclusive)" in {
      subject.scheduleAfter(new Duration(1000L)) { action(INITIAL.plus(1000)) }

      subject.runTo(INITIAL.plus(1000))

      count must be equalTo 1
      subject.now must be equalTo INITIAL.plus(1000)
    }
    "not run actions after the specified instant" in {
      subject.scheduleAfter(new Duration(2000L)) { action(INITIAL.plus(2000)) }
      subject.scheduleAfter(new Duration(1000L)) { action(INITIAL.plus(1000)) }

      subject.runTo(INITIAL.plus(1500))

      count must be equalTo 1
      subject.now must be equalTo INITIAL.plus(1500)
    }
    "not run actions that have been cancelled" in {
      val subscription = subject.scheduleAfter(new Duration(2000L)) { action(INITIAL.plus(2000)) }
      subject.scheduleAfter(new Duration(1000L)) { subscription.close(); action(INITIAL.plus(1000)) }

      subject.run()

      count must be equalTo 1
      subject.now must be equalTo INITIAL.plus(1000)
    }
    "not cancel actions that have already run" in {
      val subscription = subject.scheduleAfter(new Duration(1000L)) { action(INITIAL.plus(1000)) }
      subject.scheduleAfter(new Duration(2000L)) { subscription.close(); action(INITIAL.plus(2000)) }

      subject.run()

      count must be equalTo 2
      subject.now must be equalTo INITIAL.plus(2000)
    }
  }

}
