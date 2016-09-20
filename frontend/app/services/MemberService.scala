package services

import com.gu.config.DiscountRatePlanIds
import com.gu.i18n.Country.UK
import com.gu.i18n.{Country, CountryGroup, Currency, GBP}
import com.gu.identity.play.{IdMinimalUser, IdUser}
import com.gu.memsub.Benefit
import com.gu.memsub.subsv2.SubscriptionPlan
import com.gu.memsub.BillingPeriod.year
import com.gu.memsub.Subscriber.{FreeMember, PaidMember}
import com.gu.memsub.Subscription.{Feature, ProductRatePlanId}
import com.gu.memsub.promo._
import com.gu.memsub.{Subscription => _, _}
import com.gu.memsub.services.PromoService
import com.gu.memsub.util.Timing
import com.gu.subscriptions.Discounter
import com.gu.zuora.rest
import com.gu.zuora.soap.models.Commands._
import com.gu.memsub.promo.PromotionMatcher._
import com.gu.memsub.promo.PromotionApplicator._
import _root_.services.api.MemberService.{MemberError, PendingAmendError}
import com.gu.memsub.subsv2._
import com.gu.memsub.services.api.PaymentService
import com.gu.memsub.subsv2.services._
import com.gu.salesforce.Tier.{Partner, Patron}
import com.gu.salesforce._
import com.gu.stripe.Stripe.Customer
import com.gu.stripe.{Stripe, StripeService}
import com.gu.zuora.api.ZuoraService
import com.gu.zuora.soap.models.Results.{CreateResult, SubscribeResult, UpdateResult}
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import com.typesafe.scalalogging.LazyLogging
import controllers.IdentityRequest
import forms.MemberForm._
import model.Eventbrite.{EBCode, EBOrder, EBTicketClass}
import model.RichEvent.RichEvent
import model.PaidSubscription
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import tracking._
import utils.CampaignCode
import views.support.ThankyouSummary
import views.support.ThankyouSummary.NextPayment
import model.{Benefit => _, _}
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import scala.concurrent.Future
import scala.util.Failure
import scalaz.std.scalaFuture._
import scalaz.syntax.either._
import scalaz.syntax.std.option._
import scalaz.syntax.monad._
import views.support.MembershipCompat._

import scalaz._

object MemberService {
  import api.MemberService.MemberError

  type EitherTErr[F[_], A] = EitherT[F, MemberError, A]
  implicit val monadTrans = MonadTrans[EitherTErr]

  def featureIdsForTier(features: Seq[SoapQueries.Feature])(tier: Tier, choice: Set[FeatureChoice]): Seq[Feature.Id] = {
    def chooseFeature(choices: Set[FeatureChoice]): Seq[Feature.Id] =
      features.filter(f => choices.map(_.zuoraCode).contains(f.code))
        .map(_.id)

    tier match {
      case Patron() => chooseFeature(FeatureChoice.all)
      case Partner() => chooseFeature(choice).take(1)
      case _ => Nil
    }
  }

  def getRatePlanIdsToRemove(current: Seq[rest.RatePlan],
                             planFinder: ProductRatePlanId => Boolean,
                             discounts: DiscountRatePlanIds) = current.collect {
    case discount if discount.productRatePlanId == discounts.percentageDiscount.planId.get => discount.id
    case plan if planFinder(ProductRatePlanId(plan.productRatePlanId)) => plan.id
  }
}

class MemberService(identityService: IdentityService,
                    salesforceService: api.SalesforceService,
                    zuoraService: ZuoraService,
                    stripeService: StripeService,
                    subscriptionService: SubscriptionService[Future],
                    catalogService: CatalogService[Future],
                    promoService: PromoService,
                    paymentService: PaymentService,
                    discounter: Discounter,
                    discountIds: DiscountRatePlanIds)
    extends api.MemberService with ActivityTracking with LazyLogging  {

  import EventbriteService._
  import MemberService._

  implicit val catalog = catalogService.unsafeCatalog

  def country(contact: Contact)(implicit r: IdentityRequest): Future[Country] =
    identityService.getFullUserDetails(IdMinimalUser(contact.identityId, None))
      .map(c => c.privateFields.flatMap(_.billingCountry).orElse(c.privateFields.flatMap(_.country))
      .flatMap(CountryGroup.countryByNameOrCode)).map(_.getOrElse(Country.UK))

  override def createMember(
      user: IdMinimalUser,
      formData: JoinForm,
      identityRequest: IdentityRequest,
      fromEventId: Option[String],
      campaignCode: Option[CampaignCode],
      tier: Tier): Future[(ContactId, ZuoraSubName)] = {

    def getIdentityUserDetails(): Future[IdUser] =
      identityService.getFullUserDetails(user)(identityRequest).andThen { case Failure(e) =>
        logger.error(s"Could not get Identity user details for user ${user.id}", e)}

    def createSalesforceContact(user: IdUser): Future[ContactId] =
      salesforceService.upsert(user, formData).andThen { case Failure(e) =>
        logger.error(s"Could not create Salesforce contact for user ${user.id}", e)}

    def createStripeCustomer(paid: PaidMemberJoinForm): Future[Customer] =
      stripeService.Customer.create(user.id, paid.payment.token).andThen {
        case Failure(e) => logger.warn(s"Could not create Stripe customer for user ${user.id}", e)}

    def updateIdentity(): Future[Unit] =
      Future {
        formData.password.foreach(identityService.updateUserPassword(_, identityRequest, user.id)) // Update user password (social signin)
        identityService.updateUserFieldsBasedOnJoining(user, formData, identityRequest) // Update Identity user details in MongoDB
      }.andThen { case Failure(e) => logger.error(s"Could not update Identity for user ${user.id}", e)}

    def createPaidZuoraSubscription(sfContact: ContactId, stripeCustomer: Customer, paid: PaidMemberJoinForm): Future[String] =
      (for {
        zuoraSub <- createPaidSubscription(sfContact, paid, paid.name, paid.tier, stripeCustomer, campaignCode)
      } yield zuoraSub.subscriptionName).andThen { case Failure(e) =>
        logger.error(s"Could not create paid Zuora subscription for user ${user.id}", e)}

    def createFreeZuoraSubscription(sfContact: ContactId, formData: JoinForm) =
      (for {
        zuoraSub <- createFreeSubscription(sfContact, formData)
      } yield zuoraSub.subscriptionName).andThen { case Failure(e) =>
        logger.error(s"Could not create free Zuora subscription for user ${user.id}", e)}

    def updateSalesforceContactWithMembership(stripeCustomer: Option[Customer]): Future[ContactId] =
      salesforceService.updateMemberStatus(user, tier, stripeCustomer).andThen { case Failure(e) =>
        logger.error(s"Could not update Salesforce contact with membership status for user ${user.id}", e)}

    formData match {
      case paid: PaidMemberJoinForm => //
        for {
          stripeCustomer  <- createStripeCustomer(paid)
          idUser          <- getIdentityUserDetails()
          sfContact       <- createSalesforceContact(idUser)
          zuoraSubName    <- createPaidZuoraSubscription(sfContact, stripeCustomer, paid)
          _               <- updateSalesforceContactWithMembership(Some(stripeCustomer))  // FIXME: This should go!
          _               <- updateIdentity()
        } yield (sfContact, zuoraSubName)

      case _ =>
        for {
          idUser          <- getIdentityUserDetails()
          sfContact       <- createSalesforceContact(idUser)
          zuoraSubName    <- createFreeZuoraSubscription(sfContact, formData)
          _               <- updateSalesforceContactWithMembership(None)                  // FIXME: This should go!
          _               <- updateIdentity()
        } yield (sfContact, zuoraSubName)
    }
  }

  override def upgradeFreeSubscription(sub: FreeMember, newTier: PaidTier, form: FreeMemberChangeForm, code: Option[CampaignCode])
                                      (implicit identity: IdentityRequest): Future[MemberError \/ ContactId] = {
    (for {
      customer <- stripeService.Customer.create(sub.contact.identityId, form.payment.token).liftM
      paymentResult <- createPaymentMethod(sub.contact, customer).liftM
      memberId <- EitherT(upgradeSubscription(
        sub.subscription,
        contact = sub.contact,
        planChoice = PaidPlanChoice(newTier, form.payment.billingPeriod),
        form = form,
        customerOpt = Some(customer),
        campaignCode = code
      ))
    } yield memberId).run
  }

  override def upgradePaidSubscription(sub: PaidMember, newTier: PaidTier, form: PaidMemberChangeForm, code: Option[CampaignCode])
                                      (implicit id: IdentityRequest): Future[MemberError \/ ContactId] =
    (for {
      memberId <- EitherT(upgradeSubscription(
        sub.subscription,
        contact = sub.contact,
        planChoice = PaidPlanChoice(newTier, sub.subscription.plan.charges.billingPeriod),
        form = form,
        customerOpt = None,
        campaignCode = code
      ))
    } yield {
      track(MemberActivity("upgradeMembership", MemberData(
        sub.contact.salesforceContactId,
        sub.contact.identityId,
        sub.subscription.plan.tier)), sub.contact)
      salesforceService.metrics.putUpgrade(newTier)
      memberId}).run

  override def downgradeSubscription(subscriber: PaidMember): Future[MemberError \/ Unit] = {
    //if the member has paid upfront so they should have the higher tier until charged date has completed then be downgraded
    //otherwise use customer acceptance date (which should be in the future)
    def effectiveFrom(sub: Subscription[SubscriptionPlan.PaidMember]) = sub.plan.chargedThrough.getOrElse(sub.startDate)
    val friendRatePlanId = catalog.friend.id

    (for {
      paidSub <- EitherT(subOrPendingAmendError(subscriber.subscription))
      result <- zuoraService.downgradePlan(
        subscription = paidSub.id,
        currentRatePlanId = paidSub.plan.id,
        futureRatePlanId = friendRatePlanId,
        effectiveFrom = effectiveFrom(paidSub)).liftM
    } yield {
      salesforceService.metrics.putDowngrade(subscriber.subscription.plan.tier)
      track(
        MemberActivity(
          "downgradeMembership",
          MemberData(
            salesforceContactId = subscriber.contact.salesforceContactId,
            identityId = subscriber.contact.identityId,
            tier = subscriber.subscription.plan.tier,
            tierAmendment = Some(DowngradeAmendment(subscriber.subscription.plan.tier)) //getting effective date and subscription annual / month is proving difficult
          )),
        subscriber.contact)
    }).run
  }

  override def cancelSubscription(subscriber: com.gu.memsub.Subscriber.Member): Future[MemberError \/ Unit] = {

    val cancelDate = subscriber.subscription.plan match {
      case PaidSubscriptionPlan(_, _, _, _, _, _, chargedThrough) => chargedThrough.getOrElse(DateTime.now.toLocalDate)
      case _ => DateTime.now.toLocalDate
    }

    (for {
      _ <- zuoraService.cancelPlan(subscriber.subscription.id, subscriber.subscription.plan.id, cancelDate).liftM
    } yield {
      salesforceService.metrics.putCancel(subscriber.subscription.plan.tier)
      track(MemberActivity("cancelMembership", MemberData(
        subscriber.contact.salesforceContactId,
        subscriber.contact.identityId,
        subscriber.subscription.plan.tier)), subscriber.contact)
      track(MemberActivity("cancelMembership", MemberData(subscriber.contact.salesforceContactId, subscriber.contact.identityId, subscriber.subscription.plan.tier)), subscriber.contact)
    }).run
  }

  override def previewUpgradeSubscription(subscriber: PaidMember, newPlan: PlanChoice, code: Option[ValidPromotion[Upgrades]])
                                         (implicit i: IdentityRequest): Future[MemberError \/ BillingSchedule] = {
    (for {
      _ <- EitherT(subOrPendingAmendError(subscriber.subscription))
      country <- EitherT(country(subscriber.contact).map(\/.right))
      a <- EitherT(amend(subscriber.subscription, newPlan, Set.empty, code).map(\/.right))
      result <- EitherT(zuoraService.upgradeSubscription(a.copy(previewMode = true)).map(\/.right))
    } yield BillingSchedule.fromPreviewInvoiceItems(_ => None)(result.invoiceItems).getOrElse(
      throw new IllegalStateException(s"Sub ${subscriber.subscription.id} upgrading to ${newPlan.tier} has no bills")
    )).run
  }

  def subscriptionUpgradableTo(sub: Subscription[SubscriptionPlan.Member], newTier: PaidTier): Boolean = {
    import model.TierOrdering.upgradeOrdering
    val newPlan = catalog.findPaid(newTier)

    // The year and month plans are guaranteed to have the same currencies
    val targetCurrencies = newPlan.year.charges.price.prices.map(_.currency).toSet
    targetCurrencies.toSet.contains(sub.plan.charges.currencies) && newPlan.month.tier > sub.plan.tier
  }

  override def getMembershipSubscriptionSummary(contact: GenericSFContact): Future[ThankyouSummary] = {


    val latestSubEither = subscriptionService.either[SubscriptionPlan.FreeMember, SubscriptionPlan.PaidMember](contact).map(_.get)
    val latestSubF = latestSubEither.map(_.fold(identity,identity))

    def getSummaryViaPreview =
      for {
        sub <- latestSubF
        subEither <- latestSubEither
        paymentDetails <- paymentService.billingSchedule(sub.id)
      } yield {
        implicit val currency = sub.plan.charges.currencies.head
        def price(amount: Float) = Price(amount, sub.plan.charges.currencies.head)

        val nextPayment = for {
          amount <- paymentDetails.map(_.first.amount)
          date <- paymentDetails.map(_.first.date)
        } yield NextPayment(price(amount), date)

        val planAmount = subEither.fold(_ => Price(0.0f, GBP), _.plan.charges.price.prices.head)

        ThankyouSummary(
          startDate = sub.termStartDate,
          amountPaidToday = subEither.fold(_ => price(0.0f), _ => planAmount),
          planAmount = planAmount,
          nextPayment = nextPayment,
          renewalDate = Some(sub.termEndDate.plusDays(1)),
          initialFreePeriodOffer = false, // todo maybe sort this out
          subEither.fold(_ => BillingPeriod.year, _.plan.charges.billingPeriod) // TODO what
        )
      }

    for {
      summary <- getSummaryViaPreview
    } yield summary
  }

  override def getUsageCountWithinTerm(subscription: Subscription[SubscriptionPlan.Member], unitOfMeasure: String): Future[Option[Int]] = {
    val features = subscription.features
    val startDate = subscription.startDate.toDateTimeAtStartOfDay(DateTimeZone.forID("America/Los_Angeles"))
    zuoraService.getUsages(subscription.name, unitOfMeasure, startDate).map { usages =>
      val hasComplimentaryTickets = features.map(_.code).contains(FreeEventTickets.zuoraCode)
      if (!hasComplimentaryTickets) None else Some(usages.size)
    }
  }

  override def recordFreeEventUsage(subs: Subscription[SubscriptionPlan.Member], event: RichEvent, order: EBOrder, quantity: Int): Future[CreateResult] = {
    val description = s"event-id:${event.id};order-id:${order.id}"

    for {
      result <- zuoraService.createFreeEventUsage(
        accountId = subs.accountId,
        subscriptionNumber = subs.name,
        description = description,
        quantity = quantity
      )
    } yield {
      logger.info(s"Recorded a complimentary event ticket usage for account ${subs.accountId}, subscription: ${subs.name}, details: $description")
      result
    }
  }

  override def retrieveComplimentaryTickets(sub: Subscription[SubscriptionPlan.Member], event: RichEvent): Future[Seq[EBTicketClass]] = {
    Timing.record(salesforceService.metrics, "retrieveComplimentaryTickets") {
      for {
        usageCount <- getUsageCountWithinTerm(sub, FreeEventTickets.unitOfMeasure)
      } yield {
        val hasComplimentaryTickets = usageCount.isDefined
        val allowanceNotExceeded = usageCount.exists(_ < FreeEventTickets.allowance)
        logger.info(
          s"User ${sub.accountId} has used $usageCount tickets" ++
            s"(allowance not exceeded: $allowanceNotExceeded, is entitled: $hasComplimentaryTickets)")

        logger.info(s"Complementary tickets: ${event.internalTicketing.map(_.complimentaryTickets)}")
        if (hasComplimentaryTickets && allowanceNotExceeded)
          event.internalTicketing.map(_.complimentaryTickets).getOrElse(Nil)
        else Nil
      }
    }
  }

  override def createEBCode(subscriber: com.gu.memsub.Subscriber.Member, event: RichEvent): Future[Option[EBCode]] = {
    retrieveComplimentaryTickets(subscriber.subscription, event).flatMap { complimentaryTickets =>
      val code = DiscountCode.generate(s"A_${subscriber.contact.identityId}_${event.id}")
      val unlockedTickets = complimentaryTickets ++ event.retrieveDiscountedTickets(subscriber.subscription.plan.tier)
      event.service.createOrGetAccessCode(event, code, unlockedTickets)
    }
  }

  override def createFreeSubscription(contactId: ContactId,
                                      joinData: JoinForm): Future[SubscribeResult] = {
    val planId = joinData.planChoice.productRatePlanId
    val currency = catalog.unsafeFindFree(planId).currencyOrGBP(joinData.deliveryAddress.country.getOrElse(UK))

    (for {
      zuoraFeatures <- zuoraService.getFeatures
      result <- zuoraService.createSubscription(Subscribe(
        account = Account.stripe(contactId, currency, autopay = false),
        paymentMethod = None,
        ratePlans = NonEmptyList(RatePlan(planId.get, None)),
        name = joinData.name,
        address = joinData.deliveryAddress,
        promoCode = joinData.trackingPromoCode
      ))
    } yield result).andThen { case Failure(e) => logger.error(s"Could not create free subscription for user with salesforceContactId ${contactId.salesforceContactId}", e)}
  }

  implicit private def features = zuoraService.getFeatures

  override def createPaidSubscription(contactId: ContactId,
                                      joinData: PaidMemberForm,
                                      nameData: NameForm,
                                      tier: PaidTier,
                                      customer: Stripe.Customer,
                                      campaignCode: Option[CampaignCode]): Future[SubscribeResult] = {

    val country = joinData.zuoraAccountAddress.country
    val planChoice = PaidPlanChoice(tier,joinData.payment.billingPeriod)
    val subscribe = zuoraService.getFeatures.map { features =>

      val planId = planChoice.productRatePlanId
      val plan = RatePlan(planId.get, None, featuresPerTier(features)(planId, joinData.featureChoice).map(_.id.get))
      val currency = catalog.unsafeFindPaid(planId).currencyOrGBP(joinData.zuoraAccountAddress.country.getOrElse(UK))

      Subscribe(account = Account.stripe(contactId = contactId, currency = currency, autopay = true),
              paymentMethod = CreditCardReferenceTransaction(customer.card.id, customer.id).some,
              address = joinData.zuoraAccountAddress,
              ratePlans = NonEmptyList(plan),
              name = nameData)
    }.andThen { case Failure(e) => logger.error(s"Could not get features in tier ${tier.name} for user with salesforceContactId ${contactId.salesforceContactId}", e)}

    val promo = promoService.validateMany[NewUsers](country.getOrElse(UK), planChoice.productRatePlanId)(joinData.promoCode, joinData.trackingPromoCode).toOption.flatten
    subscribe.map(sub => promo.fold(sub)(promo => SubscribePromoApplicator.apply(promo, catalog.unsafeFindPaid(_).charges.billingPeriod, discountIds)(sub)))
             .flatMap(zuoraService.createSubscription)
             .andThen { case Failure(e) => logger.error(s"Could not create paid subscription in tier ${tier.name} for user with salesforceContactId ${contactId.salesforceContactId}", e)}
  }

  private def featuresPerTier(zuoraFeatures: Seq[SoapQueries.Feature])(productRatePlanId: ProductRatePlanId, choice: Set[FeatureChoice]): Seq[SoapQueries.Feature] = {
    def byChoice(choice: Set[FeatureChoice]) =
      zuoraFeatures.filter(f => choice.map(_.zuoraCode).contains(f.code))

    catalog.unsafeFind(productRatePlanId).tier match {
      case Patron() => byChoice(FeatureChoice.all)
      case Partner() => byChoice(choice).take(1)
      case _ => Nil
    }
  }

  def latestInvoiceItems(items: Seq[SoapQueries.InvoiceItem]): Seq[SoapQueries.InvoiceItem] = {
    if(items.isEmpty)
      items
    else {
      val sortedItems = items.sortBy(_.chargeNumber)
      sortedItems.filter(_.subscriptionId == sortedItems.last.subscriptionId)
    }
  }

  /**
    * Construct an Amend command
    */
  private def amend(sub: Subscription[SubscriptionPlan.Member], planChoice: PlanChoice, form: Set[FeatureChoice], code: Option[ValidPromotion[Upgrades]])
                   (implicit r: IdentityRequest, applicator: PromotionApplicator[Upgrades, Amend]): Future[Amend] = {

    val newPlan = catalog.unsafeFindPaid(planChoice.productRatePlanId)
    val tier = newPlan.tier

    val oldRest = zuoraService.getRestSubscription(sub.name).map(_.getOrElse(
      throw new Exception(s"REST sub not found for ${sub.id}")
    ))

    val zuoraFeatures = zuoraService.getFeatures.map { fs => featureIdsForTier(fs)(tier, form) }
    val newRatePlan = zuoraFeatures.map(fs => RatePlan(newPlan.id.get, None, fs.map(_.get)))

    (newRatePlan |@| oldRest) { case (newPln, restSub) =>
      val plansToRemove = getRatePlanIdsToRemove(restSub.ratePlans, catalog.find(_).isDefined, discountIds)
      val upgrade = Amend(sub.id.get, plansToRemove, NonEmptyList(newPln), sub.promoCode)
      code.fold(upgrade)(applicator(_, catalog.unsafeFindPaid(_).charges.billingPeriod, discountIds)(upgrade))
    }
  }

  private def upgradeSubscription(sub: Subscription[SubscriptionPlan.Member],
                                  contact: Contact,
                                  planChoice: PlanChoice,
                                  form: MemberChangeForm,
                                  customerOpt: Option[Customer],
                                  campaignCode: Option[CampaignCode])(implicit r: IdentityRequest): Future[MemberError \/ ContactId] = {

    val addressDetails = form.addressDetails
    val newPlan = catalog.unsafeFindPaid(planChoice.productRatePlanId)
    val tier = newPlan.tier

    (for {
      s <- EitherT(subOrPendingAmendError(sub))
      country <- EitherT(country(contact).map(\/.right))
      promo = promoService.validateMany[Upgrades](country, planChoice.productRatePlanId)(form.promoCode, form.trackingPromoCode).toOption.flatten
      command <- EitherT(amend(sub, planChoice, form.featureChoice, promo).map(\/.right))
      _ <- zuoraService.upgradeSubscription(command).liftM
      _ <- salesforceService.updateMemberStatus(IdMinimalUser(contact.identityId, None), newPlan.tier, None).liftM
    } yield {
      salesforceService.metrics.putUpgrade(tier)
      addressDetails.foreach(identityService.updateUserFieldsBasedOnUpgrade(contact.identityId, _))
      trackUpgrade(contact, sub, newPlan, addressDetails, campaignCode)
      contact
    }).run
  }

  private def createPaymentMethod(contactId: ContactId,
                                  customer: Stripe.Customer): Future[UpdateResult] =
    for {
      sub <- subscriptionService.current[SubscriptionPlan.Member](contactId).map(_.head)
      result <- zuoraService.createCreditCardPaymentMethod(sub.accountId, customer)
    } yield result

  private def subOrPendingAmendError[P <: Subscription[SubscriptionPlan.Member]](sub: P): Future[MemberError \/ P] =
    for {
      restSub <- zuoraService.getRestSubscription(sub.name).map(
        _.getOrElse(throw new Exception(s"Sub ${sub.name} not found with rest"))
      )
    } yield {
      if (restSub.hasPendingAmendment() || restSub.isCancelled)
        PendingAmendError(sub.name).left
      else
        sub.right
    }

  private def getPaymentSummary(memberId: ContactId): Future[PaymentSummary] =
    subscriptionService.current[SubscriptionPlan.Member](memberId).map(_.head).flatMap { sub =>
      zuoraService.getPaymentSummary(sub.name, sub.plan.currency)
    }
}
