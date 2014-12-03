package controllers

import actions.Functions._
import com.typesafe.scalalogging.slf4j.LazyLogging
import play.api.mvc.{Controller, Cookie}
import utils.TestUsers.testUsers

object Testing extends Controller with LazyLogging {

  val analyticsOffCookie = Cookie("ANALYTICS_OFF_KEY", "true", httpOnly = false)

  val AuthorisedTester = GoogleAuthenticatedStaffAction andThen isInAuthorisedGroup("membership.dev@guardian.co.uk",
    "You need to be added to the right Google Group to create test users - contact membership.dev@theguardian.com if you definitely need access.")

  def testUser = AuthorisedTester { implicit request =>
    val testUserString = testUsers.generate()
    logger.info(s"Generated test user string $testUserString for ${request.user.email}")
    Ok(views.html.testing.testUsers(testUserString)).withCookies(analyticsOffCookie)
  }

  def analyticsOff = CachedAction {
    Ok(s"${analyticsOffCookie.name} cookie dropped").withCookies(analyticsOffCookie)
  }


}
