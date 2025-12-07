package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import io.circe.generic.auto._
import us.awfl.utils._
import us.awfl.ista.ChatMessage

object BusinessAnalysis extends us.awfl.core.Workflow {
  val inputVal: Value[Input] = init[Input]("input")

  opaque type PlaceId = Value[String]
  override case class Input(placeId: PlaceId)

  case class Success(message: String)

  override type Result = Success

  case class Competitor(placeId: PlaceId) 

  case class GenerateKeywordsResult(businessInfo: Value[String], competitors: ListValue[Competitor]) // extends Value[GenerateKeywordsResult] {

  case class StepParams(placeId: PlaceId)
  val params = obj(StepParams(input.placeId))

  case class Reviews(reviews: ListValue[String])
  implicit val reviewsSpec: Spec[Reviews] = Spec { resolver =>
    Reviews(resolver.list("reviews"))
  }

  val cacheTtl = 1000 * 60 * 60 * 24
  def cacheReviews(name: String, placeId: PlaceId) = us.awfl.utils.Cache(
    name = s"cacheReviews_$name",
    collection = str("businesses.reviews"),
    id = placeId,
    thresholdMillis = cacheTtl,
    step = post[StepParams, Reviews](s"scrapeReviews_$name", "business-report/scrape-reviews", obj(StepParams(placeId))).flatMap(_.body)
  )

  val generateKeywords = post[StepParams, GenerateKeywordsResult]("generateKeywords", "business-report/generate-keywords", params).flatMap(_.body)

  val reviews = cacheReviews("businessReviews", input.placeId)

  val competitorReviews = For[Competitor, Reviews]("scrapeReviewsForCompetitors", generateKeywords.result.competitors) { competitor =>
    val cache = cacheReviews("competitorReviews", competitor.get.placeId)
    List(cache) -> cache.resultValue
  }

  case class CompetitorAndReviews(businessInfo: Value[Competitor], reviews: ListValue[String])

  val zipCompetitorReviews = us.awfl.utils.zip[Competitor, Reviews, CompetitorAndReviews](
    "zipCompetitorReviews",
    generateKeywords.result.competitors,
    competitorReviews.resultValue
  ) { case (competitor, reviews) =>
    Nil -> obj(CompetitorAndReviews(competitor, reviews.flatMap(_.reviews)))
  }

  def status(s: String, msg: Cel) = us.awfl.services.Firebase.update(s"Status_${s}", str("businesses.report"), input.placeId, obj(Map("status" -> s, "statusMsg" -> str(msg))))

  case class ReviewHighlight(
    author: Value[String],
    rating: Value[String],
    text: Value[String],
    time: Value[String]
  )

  case class CompetitorReviewComparison(
    name: Value[String],
    priceLevel: Value[String],
    rating: Value[String],
    comparisonReview: ReviewHighlight
  )

  case class RankedCompetitor(
    name: Value[String],
    rating: Value[String],
    priceLevel: Value[String],
    weightedScore: Value[String]
  )

  case class AnalysisResponse(
    targetBusinessSummary: Value[String],
    competitorAnalysis: Value[String],
    keyStrengths: Value[String],
    areasForImprovement: Value[String],
    marketPositioning: Value[String],
    recommendations: Value[String],
    recentOrUrgentConcerns: ReviewHighlight,
    positiveHighlights: ReviewHighlight,
    negativeHighlights: ReviewHighlight,
    competitorRanking: RankedCompetitor,
    competitorComparison: CompetitorReviewComparison
  )

  val buildPrompt = buildList("buildPrompt", List(
    ChatMessage("system",
      str("""You are an expert business analyst specializing in customer feedback analysis.
          Analyze the provided business and competitor data to generate comprehensive insights.
          Focus on actionable insights and specific recommendations. 
          
          In addition to general analysis, return:
          - Three recent reviews or urgent concerns.
          - Two of the most notable positive and two most notable negative reviews.
          - A ranked list of the target business among competitors based on rating and weighted by price level.
          - The two most relatable competitors with a review from each to highlight comparison.

          Use line breaks and markdown to format larger text blocks.
          Really try to identify important pieces of information that the owner might not be aware of, especially when comparing with competitors as far as local market trends.
      """.stripMargin)),

    ChatMessage("user", str(
      ("\rTarget Business:\r": Cel) + encodeJson(generateKeywords.resultValue) +
      "\rTarget Business Reviews: \r" + encodeJson(reviews.resultValue.flatMap(_.reviews)) +
      "\rCompetitors: \r" + encodeJson(zipCompetitorReviews.resultValue) +
      "\r\rPlease provide a detailed analysis following the specified format."
    ))
  ))

  val analyzeReport = us.awfl.services.Llm.chatJson[AnalysisResponse]("analyze_business", buildPrompt.resultValue)

  case class MarketAnalysis(
    totalCompetitors: Value[String],
    topCompetitors: ListValue[Competitor]
  )

  case class WorkflowSummary(
    businessInfo: Value[String],
    marketAnalysis: BaseValue[MarketAnalysis],
    analysis: BaseValue[AnalysisResponse],
    reviews: ListValue[String]
  )

  val cacheReport = us.awfl.utils.Cache("cacheReport", str("businesses.report"), input.placeId, cacheTtl, Try("cacheReportBlock", List[Step[_, _]](
    status("started", "Generating keywords..."),
    generateKeywords,
    // us.awfl.services.Firebase.create("createRecord", "businesses", input.placeId, generateKeywords.resultValue),
    status("reviews", "Scraping Google maps reviews..."),
    reviews,
    status("competitorReviews", "Scraping competitor's reviews..."),
    competitorReviews,
    zipCompetitorReviews,
    status("analyze", "Analyzing business and competitors..."),
    buildPrompt,
    analyzeReport,
  ) ->
    obj(WorkflowSummary(
      businessInfo = generateKeywords.resultValue.flatMap(_.businessInfo),
      marketAnalysis = obj(MarketAnalysis(
        totalCompetitors = str(len(generateKeywords.resultValue.flatMap(_.competitors))),
        topCompetitors = generateKeywords.resultValue.flatMap(_.competitors)
      )),
      analysis = analyzeReport.resultValue.flatMap(_.result),
      reviews = reviews.resultValue.flatMap(_.reviews)
    ))
  ))

  override def workflows = List(Workflow(List[Step[_, _]](
    cacheReport,
    status("done", "Done!")
  ) -> obj(Success(
    message = "Business report generated successfully!"
  ))))
}
