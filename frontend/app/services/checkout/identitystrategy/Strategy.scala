package services.checkout.identitystrategy

import com.gu.identity.play.IdUser
import controllers.IdentityRequest
import forms.MemberForm.CommonForm
import play.api.mvc.{RequestHeader, Result}
import services.AuthenticationService.authenticatedIdUserProvider
import services.{IdentityApi, IdentityService}

import scala.concurrent.Future

object Strategy {
  val identityService = IdentityService(IdentityApi)

  def identityStrategyFor(request: RequestHeader, form: CommonForm): Strategy = {
    implicit val idRequest = IdentityRequest(request)

    (for (user <- authenticatedIdUserProvider(request))
      yield ExistingSignedInUser(user, form)).getOrElse(NewUser.strategyFrom(form).get)
  }
}

trait Strategy {
  def ensureIdUser(checkoutFunc: (IdUser) => Future[Result]): Future[Result]
}
