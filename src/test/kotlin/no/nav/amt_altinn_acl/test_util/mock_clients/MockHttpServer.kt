package no.nav.amt_altinn_acl.test_util.mock_clients

import okhttp3.Headers
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.slf4j.LoggerFactory
import java.util.UUID


private val requestBodyCache = mutableMapOf<RecordedRequest, String>()

fun RecordedRequest.getBodyAsString(): String = requestBodyCache.getOrPut(this) { this.body.readUtf8() }

abstract class MockHttpServer(
	private val name: String
) {
	private val log = LoggerFactory.getLogger(javaClass)

	private val server = MockWebServer()
	private var lastRequestCount = 0
	private val responses = mutableMapOf<(request: RecordedRequest) -> Boolean, ResponseHolder>()

	fun start() {
		try {
			server.start()

			server.dispatcher = object : Dispatcher() {
				override fun dispatch(request: RecordedRequest): MockResponse {
					val response = responses.entries.find { it.key.invoke(request) }?.value
						?: throw IllegalStateException(
							"$name: Mock has no handler for $request\n" +
								"	Headers: \n${printHeaders(request.headers)}\n" +
								"	Body: ${request.body.readUtf8()}"
						)

					response.count = response.count + 1

					log.info("Responding [${request.method}: ${request.path}]: ${response.toString(request)}")
					return response.response.invoke(request)
				}
			}
		} catch (_: IllegalArgumentException) {
			log.info("${javaClass.simpleName} is already started")
		}
	}

	private fun addResponseHandler(
		predicate: (req: RecordedRequest) -> Boolean,
		response: (req: RecordedRequest) -> MockResponse
	): UUID {
		val id = UUID.randomUUID()
		responses[predicate] = ResponseHolder(id, response)
		return id
	}

	fun addResponseHandler(predicate: (req: RecordedRequest) -> Boolean, response: MockResponse): UUID =
		addResponseHandler(predicate) { response }

	fun addResponseHandler(path: String, response: MockResponse): UUID {
		val predicate = { req: RecordedRequest -> req.path == path }
		return addResponseHandler(predicate) { response }
	}

	fun resetHttpServer() {
		responses.clear()
		lastRequestCount = server.requestCount
	}

	fun serverUrl(): String = server.url("").toString().removeSuffix("/")

	fun requestCount(): Int = server.requestCount - lastRequestCount

	private fun printHeaders(headers: Headers): String =
		headers.joinToString("\n") { "		${it.first} : ${it.second}" }

	private data class ResponseHolder(
		val id: UUID,
		val response: (request: RecordedRequest) -> MockResponse,
		var count: Int = 0
	) {
		fun toString(request: RecordedRequest): String {
			val invokedResponse = response.invoke(request)

			val responseBody =
				invokedResponse.getBody()
					?.copy()
					?.asResponseBody()
					?.string()
					?.replace("\n", "")
					?.replace("  ", "")
					?.replace("	", "")
					?.trim()

			return "$id: count=$count status=${invokedResponse.status} body=$responseBody"
		}
	}
}
