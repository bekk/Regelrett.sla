package no.bekk.authentication

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import no.bekk.configuration.GithubActionsOidcConfig
import no.bekk.exception.AuthenticationException
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * Verifies a GitHub Actions OIDC token directly against GitHub's own JWKS,
 * independently of the Entra `auth-jwt` validation. This is a second,
 * independently-rooted trust chain - see research/m2m/ for why both are
 * required on the /api/m2m/slo endpoint.
 */
class GithubActionsOidcVerifier(private val config: GithubActionsOidcConfig) {
    private val logger = LoggerFactory.getLogger("no.bekk.authentication.GithubActionsOidcVerifier")

    private val jwkProvider = JwkProviderBuilder(URI(config.jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    fun verify(token: String): DecodedJWT {
        val unverifiedJwt = try {
            JWT.decode(token)
        } catch (e: Exception) {
            throw AuthenticationException("Malformed GitHub OIDC token", e)
        }

        val publicKey = try {
            jwkProvider.get(unverifiedJwt.keyId).publicKey as RSAPublicKey
        } catch (e: Exception) {
            throw AuthenticationException("Unable to resolve signing key for GitHub OIDC token", e)
        }

        var verification = JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withClaim("repository", config.expectedRepository)

        config.expectedRef?.let { expectedRef ->
            verification = verification.withClaim("ref", expectedRef)
        }

        return try {
            verification.build().verify(token)
        } catch (e: Exception) {
            logger.warn("GitHub OIDC token verification failed: ${e.message}")
            throw AuthenticationException("Invalid GitHub OIDC token", e)
        }
    }
}
