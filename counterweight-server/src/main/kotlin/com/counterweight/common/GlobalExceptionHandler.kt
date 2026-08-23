package com.counterweight.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant
import java.util.UUID

/** The single response shape for every failure, so the client parses one thing. */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
    /** Correlates what the user was shown with what was logged. */
    val traceId: String,
    val timestamp: Instant = Instant.now(),
    val path: String? = null,
)

/**
 * Converts everything thrown out of a controller into [ApiErrorResponse].
 *
 * The governing rule here is that **internal detail never crosses the boundary**.
 * A constraint name, a SQL fragment or a stack trace tells an attacker about the
 * schema; those go to the log against a trace id, and the caller gets a generic
 * message plus that id. Anything not explicitly handled is treated as internal.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, req: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val traceId = newTraceId()
        // 5xx is our fault and gets a stack trace; 4xx is the caller's and does not.
        if (ex.status.is5xxServerError) log.error("[{}] {}", traceId, ex.message, ex)
        else log.warn("[{}] {} {} -> {}: {}", traceId, req.method, req.requestURI, ex.code, ex.message)

        return ResponseEntity.status(ex.status).body(
            ApiErrorResponse(ex.code, ex.message, ex.details, traceId, path = req.requestURI)
        )
    }

    /** Bean-validation failures on an @Valid @RequestBody. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBeanValidation(
        ex: MethodArgumentNotValidException,
        req: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val fields = ex.bindingResult.allErrors.associate { err ->
            val field = (err as? FieldError)?.field ?: err.objectName
            field to (err.defaultMessage ?: "is invalid")
        }
        val traceId = newTraceId()
        log.warn("[{}] validation failed on {} {}: {}", traceId, req.method, req.requestURI, fields)
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                "VALIDATION_FAILED",
                "Some details need correcting.",
                mapOf("fields" to fields),
                traceId,
                path = req.requestURI,
            )
        )
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException, req: HttpServletRequest) =
        badRequest("VALIDATION_FAILED", "'${ex.parameterName}' is required.", req)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException, req: HttpServletRequest) =
        // Deliberately does not echo the value back — it lands in logs and in
        // any error page, and reflecting caller input is how XSS gets a foothold.
        badRequest("VALIDATION_FAILED", "'${ex.name}' is not in the expected format.", req)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException, req: HttpServletRequest) =
        badRequest("MALFORMED_REQUEST", "The request body could not be read.", req)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException, req: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val traceId = newTraceId()
        log.warn("[{}] access denied: {} {}", traceId, req.method, req.requestURI)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse("FORBIDDEN", "You do not have permission to do that.", emptyMap(), traceId, path = req.requestURI)
        )
    }

    /**
     * A database constraint fired. These are real invariants — the last
     * SYSTEM_ADMIN guard, the oversell CHECK, a duplicate username — but the
     * constraint name describes the schema, so it is logged and not returned.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException, req: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val traceId = newTraceId()
        log.warn("[{}] constraint violation on {} {}: {}", traceId, req.method, req.requestURI, ex.mostSpecificCause.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                "CONFLICT",
                "That change conflicts with existing data and was not saved.",
                emptyMap(), traceId, path = req.requestURI,
            )
        )
    }

    /**
     * A path nothing is mapped to.
     *
     * Spring raises this from the static-resource handler, which is the last
     * thing to see a request nobody claimed — so without this it lands in the
     * catch-all below and is reported as an internal error with a stack trace.
     * A caller asking for a route that does not exist has made a 404, and
     * logging it as a server fault buries the faults that are real.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoRoute(ex: NoResourceFoundException, req: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val traceId = newTraceId()
        log.warn("[{}] no route for {} {}", traceId, req.method, req.requestURI)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                "NOT_FOUND", "That address does not exist on this server.",
                emptyMap(), traceId, path = req.requestURI,
            )
        )
    }

    /**
     * The right address, the wrong verb.
     *
     * Sibling of the handler above and missing for the same reason: without it
     * a PUT to a DELETE-only route is reported as an internal server error,
     * which blames this server for a caller's mistake and buries the faults
     * that are real. The `Allow` header is what makes the answer usable —
     * it names the methods the route does take.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleWrongMethod(
        ex: HttpRequestMethodNotSupportedException,
        req: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val traceId = newTraceId()
        val allowed = ex.supportedMethods?.sorted().orEmpty()
        log.warn("[{}] {} not allowed on {}", traceId, req.method, req.requestURI)
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .apply { if (allowed.isNotEmpty()) header("Allow", allowed.joinToString(", ")) }
            .body(
                ApiErrorResponse(
                    "METHOD_NOT_ALLOWED",
                    if (allowed.isEmpty()) "That address does not accept ${req.method}."
                    else "That address accepts ${allowed.joinToString(", ")}, not ${req.method}.",
                    emptyMap(), traceId, path = req.requestURI,
                )
            )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, req: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val traceId = newTraceId()
        log.error("[{}] unhandled on {} {}", traceId, req.method, req.requestURI, ex)
        return ResponseEntity.internalServerError().body(
            ApiErrorResponse(
                "INTERNAL_ERROR",
                "Something went wrong on our side. Quote reference $traceId if you report this.",
                emptyMap(), traceId, path = req.requestURI,
            )
        )
    }

    private fun badRequest(code: String, message: String, req: HttpServletRequest) =
        ResponseEntity.badRequest().body(
            ApiErrorResponse(code, message, emptyMap(), newTraceId(), path = req.requestURI)
        )

    private fun newTraceId() = UUID.randomUUID().toString().take(8)
}
