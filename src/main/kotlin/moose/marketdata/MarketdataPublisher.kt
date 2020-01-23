package moose.marketdata

import io.vertx.core.AbstractVerticle
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import moose.Address
import moose.ErrorCodes
import moose.MarketDataAction
import moose.Timestamp
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.ZoneId

class MarketDataPublisher : AbstractVerticle() {
    private companion object {
        val logger: Logger = LoggerFactory.getLogger(MarketDataPublisher::class.java)
    }

    private val snapshot = mutableMapOf<String, MarketData>()

    // use data class ?
    // https://github.com/vert-x3/vertx-lang-kotlin/issues/43
    override fun start() {
        vertx.eventBus().consumer<JsonObject>(Address.marketdata_publisher.name) { m ->
            if ((MarketDataAction.action.name) !in m.headers()) {
                logger.error("No action header specified for message with headers {} and body {}",
                        m.headers(), m.body().encodePrettily())
                m.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal, "No action header specified")
                return@consumer
            }
            when (val action = m.headers()[MarketDataAction.action.name]) {
                MarketDataAction.tick.name -> {
                    val md = m.body().mapTo(MarketData::class.java)
                    snapshot[md.ticker.name] = md
                    publishTick(md)
                }
                MarketDataAction.init_paint.name ->
                    initPaint(m)
                else -> {
                    m.fail(ErrorCodes.BAD_ACTION.ordinal, "Bad action: $action")
                    logger.error("Unknown market data action {}", action)
                }
            }
        }
    }

    private fun marketDataToJson(marketData:MarketData, zone: ZoneId): JsonObject{
        fun formatEpoch(tick: JsonObject, field: String){
            tick.put(field,  Timestamp.formatEpoch(tick.getLong(field), zone))
        }
        val md = JsonObject.mapFrom(marketData)
        formatEpoch(md, "publishTime")
        formatEpoch(md.getJsonObject("payload"), "receivedTime")
        return md
    }

    private fun publishTick(marketData: MarketData){
        marketData.publishTime = System.currentTimeMillis()
        vertx.eventBus().publish(Address.marketdata_status.name, marketDataToJson(marketData, ZoneId.systemDefault()))
        logger.debug("published market data {}",marketData)
    }

    private fun initPaint(m : Message<JsonObject>, zone:ZoneId = ZoneId.systemDefault()) {
        m.reply(JsonArray(snapshot.map {marketDataToJson(it.value, zone)}))
    }
}

