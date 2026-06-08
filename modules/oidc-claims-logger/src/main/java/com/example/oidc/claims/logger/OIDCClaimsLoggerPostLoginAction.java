package com.example.oidc.claims.logger;

import com.liferay.portal.kernel.events.ActionException;
import com.liferay.portal.kernel.events.LifecycleAction;
import com.liferay.portal.kernel.events.LifecycleEvent;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.security.sso.openid.connect.OpenIdConnectSession;
import com.liferay.portal.security.sso.openid.connect.constants.OpenIdConnectWebKeys;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;

import java.util.Base64;

import org.osgi.service.component.annotations.Component;

/**
 * Équivalent fonctionnel d'une « surcharge » de la classe Liferay
 * {@code com.liferay.portal.security.sso.openid.connect.internal.OIDCUserInfoProcessor}.
 *
 * <p>
 * Cette classe Liferay n'est PAS surchargeable proprement en OSGi : elle est
 * publiée comme service sous son propre type concret et son package
 * {@code ...openid.connect.internal} est déclaré en {@code Private-Package}
 * (non exporté). Un module externe ne peut donc ni l'{@code extends} ni
 * enregistrer un service du même type qui serait injecté à sa place (split
 * package → type incompatible).
 * </p>
 *
 * <p>
 * On obtient le même résultat visible — afficher le JSON complet des claims
 * reçus lors de l'authentification OIDC — via une {@link LifecycleAction}
 * post-login : on récupère l'access token depuis la session OpenID Connect, on
 * décode son payload JWT (qui, côté Keycloak, porte l'intégralité des claims
 * mappés, y compris {@code momo}) et on le logge formaté.
 * </p>
 *
 * <p>
 * Ne fait rien si le login n'est pas OIDC (mot de passe local, etc.).
 * </p>
 */
@Component(
	immediate = true,
	property = "key=login.events.post",
	service = LifecycleAction.class
)
public class OIDCClaimsLoggerPostLoginAction implements LifecycleAction {

	@Override
	public void processLifecycleEvent(LifecycleEvent lifecycleEvent)
		throws ActionException {

		HttpServletRequest httpServletRequest = lifecycleEvent.getRequest();

		if (httpServletRequest == null) {
			return;
		}

		try {
			_logClaims(httpServletRequest);
		}
		catch (Exception exception) {
			_log.error(
				"Unable to log OIDC claims for current login", exception);
		}
	}

	private JSONObject _decodeJWTPayload(String jwt) {
		String[] parts = jwt.split("\\.");

		if (parts.length < 2) {
			return null;
		}

		try {
			String payload = new String(
				Base64.getUrlDecoder(
				).decode(
					parts[1]
				),
				StandardCharsets.UTF_8);

			return JSONFactoryUtil.createJSONObject(payload);
		}
		catch (Exception exception) {
			_log.warn(
				"Cannot decode JWT payload to display OIDC claims: " +
					exception.getMessage());

			return null;
		}
	}

	private void _logClaims(HttpServletRequest httpServletRequest)
		throws Exception {

		HttpSession httpSession = httpServletRequest.getSession(false);

		if (httpSession == null) {
			return;
		}

		Object sessionAttribute = httpSession.getAttribute(
			OpenIdConnectWebKeys.OPEN_ID_CONNECT_SESSION);

		if (!(sessionAttribute instanceof OpenIdConnectSession)) {

			// Login non-OIDC (mot de passe local, etc.) : rien à afficher.

			return;
		}

		OpenIdConnectSession openIdConnectSession =
			(OpenIdConnectSession)sessionAttribute;

		String accessTokenValue = openIdConnectSession.getAccessTokenValue();

		if ((accessTokenValue == null) || accessTokenValue.isEmpty()) {
			return;
		}

		JSONObject claimsJSONObject = _decodeJWTPayload(accessTokenValue);

		if (claimsJSONObject == null) {
			return;
		}

		_log.info(
			"OIDC claims reçus lors de l'authentification (provider=" +
				openIdConnectSession.getOpenIdProviderName() + ") :\n" +
					claimsJSONObject.toString(3));
	}

	private static final Log _log = LogFactoryUtil.getLog(
		OIDCClaimsLoggerPostLoginAction.class);

}
