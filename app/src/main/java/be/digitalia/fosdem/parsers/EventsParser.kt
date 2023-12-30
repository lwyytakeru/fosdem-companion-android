package be.digitalia.fosdem.parsers

import be.digitalia.fosdem.model.Attachment
import be.digitalia.fosdem.model.Day
import be.digitalia.fosdem.model.DetailedEvent
import be.digitalia.fosdem.model.Event
import be.digitalia.fosdem.model.EventDetails
import be.digitalia.fosdem.model.Link
import be.digitalia.fosdem.model.Person
import be.digitalia.fosdem.model.Track
import be.digitalia.fosdem.utils.isEndDocument
import be.digitalia.fosdem.utils.isNextEndTag
import be.digitalia.fosdem.utils.isStartTag
import be.digitalia.fosdem.utils.skipToEndTag
import be.digitalia.fosdem.utils.toInstant
import be.digitalia.fosdem.utils.xmlPullParserFactory
import okio.BufferedSource
import org.xmlpull.v1.XmlPullParser
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Named

/**
 * Main parser for FOSDEM schedule data in pentabarf XML format.
 *
 * @author Christophe Beyls
 */
class EventsParser @Inject constructor(
    @Named("Conference") private val conferenceZoneId: ZoneId
) : Parser<Sequence<DetailedEvent>> {

    override fun parse(source: BufferedSource): Sequence<DetailedEvent> {
        val parser: XmlPullParser = xmlPullParserFactory.newPullParser().apply {
            setInput(source.inputStream(), null)
        }
        return sequence {
            while (!parser.isEndDocument) {
                if (parser.isStartTag("schedule")) {
                    while (!parser.isNextEndTag("schedule")) {
                        if (parser.isStartTag) {
                            when (parser.name) {
                                "day" -> {
                                    val date = LocalDate.parse(
                                        parser.getAttributeValue(null, "date")
                                    )
                                    val day = Day(
                                        index = parser.getAttributeValue(null, "index")!!.toInt(),
                                        date = date,
                                        startTime = date
                                            .atTime(DAY_START_TIME)
                                            .toInstant(conferenceZoneId),
                                        endTime = date
                                            .atTime(DAY_END_TIME)
                                            .toInstant(conferenceZoneId)
                                    )

                                    while (!parser.isNextEndTag("day")) {
                                        if (parser.isStartTag("room")) {
                                            val roomName: String? =
                                                parser.getAttributeValue(null, "name")

                                            while (!parser.isNextEndTag("room")) {
                                                if (parser.isStartTag("event")) {
                                                    yield(parseEvent(parser, day, roomName))
                                                }
                                            }
                                        }
                                    }
                                }

                                else -> parser.skipToEndTag()
                            }
                        }
                    }
                }
                parser.next()
            }
        }
    }

    private fun parseEvent(parser: XmlPullParser, day: Day, roomName: String?): DetailedEvent {
        val id = parser.getAttributeValue(null, "id")!!.toLong()
        var startTime: Instant? = null
        var duration: String? = null
        var slug: String? = null
        var title: String? = null
        var subTitle: String? = null
        var trackName = ""
        var trackType = Track.Type.other
        var abstractText: String? = null
        var description: String? = null
        val persons = mutableListOf<Person>()
        val attachments = mutableListOf<Attachment>()
        val links = mutableListOf<Link>()

        while (!parser.isNextEndTag("event")) {
            if (parser.isStartTag) {
                when (parser.name) {
                    "start" -> {
                        val timeString = parser.nextText()
                        if (!timeString.isNullOrEmpty()) {
                            startTime = day.date
                                .atTime(LocalTime.ofSecondOfDay(parseTimeAsSeconds(timeString)))
                                .toInstant(conferenceZoneId)
                        }
                    }
                    "duration" -> duration = parser.nextText()
                    "slug" -> slug = parser.nextText()
                    "title" -> title = parser.nextText()
                    "subtitle" -> subTitle = parser.nextText()
                    "track" -> trackName = parser.nextText()
                    "type" -> try {
                        trackType = enumValueOf(parser.nextText())
                    } catch (e: Exception) {
                        // trackType will be "other"
                    }
                    "abstract" -> abstractText = parser.nextText()
                    "description" -> description = parser.nextText()
                    "persons" -> while (!parser.isNextEndTag("persons")) {
                        if (parser.isStartTag("person")) {
                            val person = Person(
                                    id = parser.getAttributeValue(null, "id")!!.toLong(),
                                    name = parser.nextText()!!
                            )
                            persons += person
                        }
                    }
                    "attachments" -> while (!parser.isNextEndTag("attachments")) {
                        if (parser.isStartTag("attachment")) {
                            val attachmentType = parser.getAttributeValue(null, "type")
                            val attachment = Attachment(
                                eventId = id,
                                url = parser.getAttributeValue(null, "href")!!,
                                description = parser.nextText().let { attachmentDescription ->
                                    // Use the type to replace the description if absent
                                    if (attachmentDescription.isNullOrBlank() && attachmentType != null) {
                                        attachmentType.replaceFirstChar { it.uppercaseChar() }
                                    } else {
                                        attachmentDescription
                                    }
                                }
                            )
                            attachments += attachment
                        }
                    }
                    "links" -> while (!parser.isNextEndTag("links")) {
                        if (parser.isStartTag("link")) {
                            val link = Link(
                                    eventId = id,
                                    url = parser.getAttributeValue(null, "href")!!,
                                    description = parser.nextText()
                            )
                            links += link
                        }
                    }
                    else -> parser.skipToEndTag()
                }
            }
        }

        val endTime = if (startTime != null && !duration.isNullOrEmpty()) {
            startTime + Duration.ofSeconds(parseTimeAsSeconds(duration))
        } else null

        val event = Event(
                id = id,
                day = day,
                roomName = roomName,
                startTime = startTime,
                endTime = endTime,
                slug = slug,
                title = title,
                subTitle = subTitle,
                track = Track(name = trackName, type = trackType),
                abstractText = abstractText,
                description = description,
                personsSummary = null
        )
        val details = EventDetails(
                persons = persons,
                attachments = attachments,
                links = links
        )
        return DetailedEvent(event, details)
    }

    /**
     * Return the total number of seconds of a string in the "hh:mm" or "hh:mm:ss" format,
     * without allocating heap memory.
     *
     * @param time string in the "hh:mm" or "hh:mm:ss" format
     */
    private fun parseTimeAsSeconds(time: String): Long {
        // hours
        var result = time[0].digitToInt() * 10 + time[1].digitToInt()
        // minutes
        result = result * 60 + time[3].digitToInt() * 10 + time[4].digitToInt()
        // seconds
        result *= 60
        if (time.length >= 8) {
            result += time[6].digitToInt() * 10 + time[7].digitToInt()
        }
        return result.toLong()
    }

    companion object {
        private val DAY_START_TIME = LocalTime.of(8, 30)
        private val DAY_END_TIME = LocalTime.of(19, 0)
    }
}