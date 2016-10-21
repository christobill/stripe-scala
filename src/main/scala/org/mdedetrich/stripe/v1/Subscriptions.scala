package org.mdedetrich.stripe.v1

import java.time.OffsetDateTime
import com.typesafe.scalalogging.LazyLogging
import enumeratum._
import org.mdedetrich.playjson.Utils._
import org.mdedetrich.stripe.v1.Discounts.Discount
import org.mdedetrich.stripe.v1.Plans.Plan
import org.mdedetrich.stripe.v1.Sources.NumberCardSource
import org.mdedetrich.stripe.{ApiKey, Endpoint, IdempotencyKey}
import play.api.data.validation.ValidationError
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Try

/**
  * @see https://stripe.com/docs/api#subscriptions
  */
object Subscriptions extends LazyLogging {

  sealed abstract class Status(val id: String) extends EnumEntry {
    override val entryName = id
  }

  object Status extends Enum[Status] {

    val values = findValues

    case object Trialing extends Status("trialing")

    case object Active extends Status("active")

    case object PastDue extends Status("past_due")

    case object Canceled extends Status("canceled")

    case object Unpaid extends Status("unpaid")
  }

  implicit val statusFormats = EnumFormats.formats(Status, insensitive = true)

  /**
    * @see https://stripe.com/docs/api#subscription_object
    * @param id
    * @param applicationFeePercent A positive decimal that represents the fee
    *                              percentage of the subscription invoice amount
    *                              that will be transferred to the application
    *                              owner’s Stripe account each billing period.
    * @param cancelAtPeriodEnd     If the subscription has been canceled with the
    *                              at_period_end flag set to true,
    *                              [[cancelAtPeriodEnd]] on the subscription will
    *                              be true. You can use this attribute to determine
    *                              whether a subscription that has a status of
    *                              active is scheduled to be canceled at the end
    *                              of the current period.
    * @param canceledAt            If the subscription has been canceled, the date
    *                              of that cancellation. If the subscription was
    *                              canceled with [[cancelAtPeriodEnd]], [[canceledAt]]
    *                              will still reflect the date of the initial cancellation
    *                              request, not the end of the subscription period when
    *                              the subscription is automatically moved to a canceled state.
    * @param currentPeriodEnd      End of the current period that the
    *                              subscription has been invoiced for.
    *                              At the end of this period, a new
    *                              invoice will be created.
    * @param currentPeriodStart    Start of the current period that the
    *                              subscription has been invoiced for
    * @param customer
    * @param discount              Describes the current discount applied
    *                              to this subscription, if there is one. When
    *                              billing, a discount applied to a subscription
    *                              overrides a discount applied on a customer-wide
    *                              basis.
    * @param endedAt               If the subscription has ended (either because
    *                              it was canceled or because the customer was
    *                              switched to a subscription to a new plan), the date
    *                              the subscription ended
    * @param metadata              A set of key/value pairs that you can
    *                              attach to a subscription object. It
    *                              can be useful for storing additional
    *                              information about the subscription in
    *                              a structured format.
    * @param plan                  Hash describing the plan the customer is subscribed to
    * @param quantity
    * @param start                 Date the subscription started
    * @param status                Possible values are [[Status.Trialing]], [[Status.Active]],
    *                              [[Status.PastDue]], [[Status.Canceled]], or [[Status.Unpaid]].
    *                              A subscription still in its trial period is trialing and
    *                              moves to active when the trial period is over. When payment
    *                              to renew the subscription fails, the subscription becomes
    *                              [[Status.PastDue]]. After Stripe has exhausted all payment
    *                              retry attempts, the subscription ends up with a status of either
    *                              [[Status.Canceled]] or [[Status.Unpaid]] depending on your retry
    *                              settings. Note that when a subscription has a status of
    *                              [[Status.Unpaid]], no subsequent invoices will be attempted
    *                              (invoices will be created, but then immediately automatically
    *                              closed. Additionally, updating customer card details will
    *                              not lead to Stripe retrying the latest invoice.). After
    *                              receiving updated card details from a customer,
    *                              you may choose to reopen and pay their closed invoices.
    * @param taxPercent            If provided, each invoice created by this
    *                              subscription will apply the tax rate,
    *                              increasing the amount billed to the customer.
    * @param trialEnd              If the subscription has a trial,
    *                              the end of that trial.
    * @param trialStart            If the subscription has a trial,
    *                              the beginning of that trial.
    */
  case class Subscription(id: String,
                          applicationFeePercent: Option[BigDecimal],
                          cancelAtPeriodEnd: Boolean,
                          canceledAt: Option[OffsetDateTime],
                          currentPeriodEnd: OffsetDateTime,
                          currentPeriodStart: OffsetDateTime,
                          customer: String,
                          discount: Option[Discount],
                          endedAt: Option[OffsetDateTime],
                          metadata: Option[Map[String, String]],
                          plan: Plan,
                          quantity: Long,
                          start: OffsetDateTime,
                          status: Status,
                          taxPercent: Option[BigDecimal],
                          trialEnd: Option[OffsetDateTime],
                          trialStart: Option[OffsetDateTime])

  object Subscription {
    def default(id: String,
                cancelAtPeriod: Boolean,
                customer: String,
                currentPeriodEnd: OffsetDateTime,
                currentPeriodStart: OffsetDateTime,
                plan: Plan,
                quantity: Long,
                start: OffsetDateTime,
                status: Status) = Subscription(
        id,
        None,
        cancelAtPeriod,
        None,
        currentPeriodEnd,
        currentPeriodStart,
        customer,
        None,
        None,
        None,
        plan,
        quantity,
        start,
        status,
        None,
        None,
        None
    )
  }

  implicit val subscriptionReads: Reads[Subscription] = (
      (__ \ "id").read[String] ~
      (__ \ "application_fee_percent").readNullable[BigDecimal] ~
      (__ \ "cancel_at_period_end").read[Boolean] ~
      (__ \ "canceled_at").readNullable[OffsetDateTime](stripeDateTimeReads) ~
      (__ \ "current_period_end").read[OffsetDateTime](stripeDateTimeReads) ~
      (__ \ "current_period_start").read[OffsetDateTime](stripeDateTimeReads) ~
      (__ \ "customer").read[String] ~
      (__ \ "discount").readNullable[Discount] ~
      (__ \ "ended_at").readNullable[OffsetDateTime](stripeDateTimeReads) ~
      (__ \ "metadata").readNullableOrEmptyJsObject[Map[String, String]] ~
      (__ \ "plan").read[Plan] ~
      (__ \ "quantity").read[Long] ~
      (__ \ "start").read[OffsetDateTime](stripeDateTimeReads) ~
      (__ \ "status").read[Status] ~
      (__ \ "tax_percent").readNullable[BigDecimal] ~
      (__ \ "trial_end").readNullable[OffsetDateTime](stripeDateTimeReads) ~
      (__ \ "trial_start").readNullable[OffsetDateTime](stripeDateTimeReads)
  ).tupled.map((Subscription.apply _).tupled)

  implicit val subscriptionWrites: Writes[Subscription] = Writes(
      (subscription: Subscription) =>
        Json.obj(
            "id" -> subscription.id,
            "object" -> "subscription",
            "application_fee_percent" -> subscription.applicationFeePercent,
            "cancel_at_period_end" -> subscription.cancelAtPeriodEnd,
            "canceled_at" -> subscription.canceledAt.map(x =>
                  Json.toJson(x)(stripeDateTimeWrites)),
            "current_period_end" -> Json.toJson(subscription.currentPeriodEnd)(
                stripeDateTimeWrites),
            "current_period_start" -> Json.toJson(
                subscription.currentPeriodStart)(stripeDateTimeWrites),
            "customer" -> subscription.customer,
            "discount" -> subscription.discount,
            "ended_at" -> subscription.endedAt.map(x =>
                  Json.toJson(x)(stripeDateTimeWrites)),
            "metadata" -> subscription.metadata,
            "plan" -> subscription.plan,
            "quantity" -> subscription.quantity,
            "start" -> Json.toJson(subscription.start)(stripeDateTimeWrites),
            "status" -> subscription.status,
            "tax_percent" -> subscription.taxPercent,
            "trial_end" -> subscription.trialEnd.map(x =>
                  Json.toJson(x)(stripeDateTimeWrites)),
            "trial_start" -> subscription.trialStart.map(x =>
                  Json.toJson(x)(stripeDateTimeWrites))
      ))

  sealed abstract class Source

  object Source {

    case class Token(id: String) extends Source

    /**
      * @see https://stripe.com/docs/api#create_subscription-source
      * @param expMonth Two digit number representing the card's
      *                 expiration month.
      * @param expYear  Two or four digit number representing
      *                 the card's expiration year.
      * @param number   The card number, as a string
      *                 without any separators.
      * @param addressCountry
      * @param addressLine1
      * @param addressLine2
      * @param addressState
      * @param addressZip
      * @param cvc      Card security code. Required unless
      *                 your account is registered in
      *                 Australia, Canada, or the United States.
      *                 Highly recommended to always include
      *                 this value.
      * @param name     Cardholder's full name.
      */
    case class Card(expMonth: Int,
                    expYear: Int,
                    number: String,
                    addressCountry: Option[String],
                    addressLine1: Option[String],
                    addressLine2: Option[String],
                    addressState: Option[String],
                    addressZip: Option[String],
                    cvc: Option[String],
                    name: Option[String])
        extends Source
        with NumberCardSource
  }

  implicit val sourceReads: Reads[Source] = {
    __.read[JsValue].flatMap {
      case jsObject: JsObject =>
        (
            (__ \ "exp_month").read[Int] ~
            (__ \ "exp_year").read[Int] ~
            (__ \ "number").read[String] ~
            (__ \ "address_country").readNullable[String] ~
            (__ \ "address_line1").readNullable[String] ~
            (__ \ "address_line2").readNullable[String] ~
            (__ \ "address_state").readNullable[String] ~
            (__ \ "address_zip").readNullable[String] ~
            (__ \ "cvc").readNullable[String] ~
            (__ \ "name").readNullable[String]
        ).tupled.map((Source.Card.apply _).tupled)
      case jsString: JsString =>
        __.read[String].map { tokenId =>
          Source.Token(tokenId)
        }
      case _ =>
        Reads[Source](_ => JsError(ValidationError("InvalidSource")))
    }
  }

  implicit val sourceWrites: Writes[Source] = Writes(
      (source: Source) =>
        source match {
      case Source.Token(id) =>
        JsString(id)
      case Source.Card(
          expMonth,
          expYear,
          number,
          addressCountry,
          addressLine1,
          addressLine2,
          addressState,
          addressZip,
          cvc,
          name
          ) =>
        Json.obj(
            "object" -> "card",
            "exp_month" -> expMonth,
            "exp_year" -> expYear,
            "number" -> number,
            "address_country" -> addressCountry,
            "address_line1" -> addressLine1,
            "address_line2" -> addressLine2,
            "address_state" -> addressState,
            "address_zip" -> addressZip,
            "cvc" -> cvc,
            "name" -> name
        )
  })

  /**
    * @see https://stripe.com/docs/api#create_subscription-source
    * @param applicationFeePercent A positive decimal (with
    *                              at most two decimal places) between
    *                              1 and 100. This represents the percentage
    *                              of the subscription invoice subtotal
    *                              that will be transferred to the
    *                              application owner’s Stripe account.
    *                              The request must be made with an
    *                              OAuth key in order to set an application
    *                              fee percentage. For more information,
    *                              see the application fees
    * @param coupon                The code of the coupon to apply to
    *                              this subscription. A coupon applied to
    *                              a subscription will only affect
    *                              invoices created for that particular
    *                              subscription.
    * @param plan                  The identifier of the plan
    *                              to subscribe the customer to.
    * @param source                The source can either be a token, like the ones
    *                              returned by our Stripe.js, or a dictionary
    *                              containing a user's credit card details
    *                              (with the options shown below). You must
    *                              provide a source if the customer does not
    *                              already have a valid source attached,
    *                              and you are subscribing the customer
    *                              for a plan that is not free. Passing
    *                              [[source]] will create a new source object,
    *                              make it the customer default source,
    *                              and delete the old customer default
    *                              if one exists. If you want to add an
    *                              additional source to use with subscriptions,
    *                              instead use the card creation API to add
    *                              the card and then the customer update API
    *                              to set it as the default. Whenever
    *                              you attach a card to a customer, Stripe
    *                              will automatically validate the card.
    * @param quantity              The quantity you'd like to apply to
    *                              the subscription you're creating.
    *                              For example, if your plan is $10/user/month,
    *                              and your customer has 5 users, you could pass
    *                              5 as the quantity to have the customer
    *                              charged $50 (5 x $10) monthly. If you
    *                              update a subscription but don't change
    *                              the plan ID (e.g. changing only the trial_end),
    *                              the subscription will inherit the old
    *                              subscription's quantity attribute unless
    *                              you pass a new quantity parameter. If you
    *                              update a subscription and change the plan ID,
    *                              the new subscription will not inherit the
    *                              quantity attribute and will default to
    *                              1 unless you pass a quantity parameter.
    * @param metadata              A set of key/value pairs that you
    *                              can attach to a subscription object.
    *                              It can be useful for storing additional
    *                              information about the subscription in a
    *                              structured format.
    * @param taxPercent            A positive decimal (with at most
    *                              two decimal places) between 1 and 100.
    *                              This represents the percentage of the
    *                              subscription invoice subtotal that
    *                              will be calculated and added as tax to
    *                              the final amount each billing period.
    *                              For example, a plan which charges
    *                              $10/month with a [[taxPercent]] of 20.0
    *                              will charge $12 per invoice.
    * @param trialEnd              Unix timestamp representing the end
    *                              of the trial period the customer
    *                              will get before being charged for
    *                              the first time. If set, [[trialEnd]]
    *                              will override the default trial period
    *                              of the plan the customer is being
    *                              subscribed to. The special value now
    *                              can be provided to end the customer's
    *                              trial immediately.
    */
  case class SubscriptionInput(applicationFeePercent: Option[BigDecimal],
                               coupon: Option[String],
                               plan: String,
                               source: Option[Source],
                               quantity: Option[Long],
                               metadata: Option[Map[String, String]],
                               taxPercent: Option[BigDecimal],
                               trialEnd: Option[OffsetDateTime])

  object SubscriptionInput {
    def default(plan: String): SubscriptionInput = SubscriptionInput(
        None,
        None,
        plan,
        None,
        None,
        None,
        None,
        None
    )
  }

  def create(customerId: String, subscriptionInput: SubscriptionInput)(
      idempotencyKey: Option[IdempotencyKey] = None)(
      implicit apiKey: ApiKey,
      endpoint: Endpoint): Future[Try[Subscription]] = {

    val postFormParameters: Map[String, String] = {
      Map(
          "application_fee_percent" -> subscriptionInput.applicationFeePercent
            .map(_.toString()),
          "coupon" -> subscriptionInput.coupon,
          "plan" -> Option(subscriptionInput.plan),
          "quantity" -> subscriptionInput.quantity.map(_.toString),
          "tax_percent" -> subscriptionInput.taxPercent.map(_.toString()),
          "trial_end" -> subscriptionInput.trialEnd.map(
              stripeDateTimeParamWrites)
      ).collect {
        case (k, Some(v)) => (k, v)
      }
    } ++ mapToPostParams(subscriptionInput.metadata, "metadata") ++ {
      subscriptionInput.source match {
        case Some(
            Source.Card(
            expMonth,
            expYear,
            number,
            addressCountry,
            addressLine1,
            addressLine2,
            addressState,
            addressZip,
            cvc,
            name
            )) =>
          val map = Map(
              "exp_month" -> Option(expMonth.toString),
              "exp_year" -> Option(expYear.toString),
              "number" -> Option(number),
              "address_country" -> addressCountry,
              "address_line1" -> addressLine1,
              "address_line2" -> addressLine2,
              "address_state" -> addressState,
              "address_zip" -> addressZip,
              "cvc" -> cvc,
              "name" -> name
          ).collect {
            case (k, Some(v)) => (k, v)
          }

          mapToPostParams(Option(map), "card")
        case Some(Source.Token(id)) =>
          Map("source" -> id)
        case None =>
          Map.empty
      }
    }

    logger.debug(s"Generated POST form parameters is $postFormParameters")

    val finalUrl = endpoint.url + s"/v1/customers/$customerId/subscriptions"

    createRequestPOST[Subscription](
        finalUrl, postFormParameters, idempotencyKey, logger)
  }
}
