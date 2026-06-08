package com.example.oidc.claims.logger;

import com.liferay.expando.kernel.model.ExpandoBridge;
import com.liferay.portal.kernel.events.ActionException;
import com.liferay.portal.kernel.events.LifecycleAction;
import com.liferay.portal.kernel.events.LifecycleEvent;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.security.sso.openid.connect.OpenIdConnectSession;
import com.liferay.portal.security.sso.openid.connect.constants.OpenIdConnectWebKeys;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Mappe des claims OpenID Connect (Keycloak) vers des <b>custom fields</b>
 * (expando) de l'utilisateur Liferay, après chaque login.
 *
 * <p>
 * Pourquoi en code plutôt qu'en configuration ? Sur Liferay DXP 2025.q3.0, la
 * classe {@code OIDCUserInfoProcessor} n'honore dans son « User Info Mapper »
 * JSON que les sections fixes {@code user}, {@code contact}, {@code address},
 * {@code phone}, {@code users_roles}, {@code users_groups} (vérifié sur le
 * bytecode réel). Il n'existe AUCUNE section permettant de mapper un claim vers
 * un custom field utilisateur dans cette version (la fonctionnalité n'a été
 * ajoutée que dans des versions ultérieures). On le fait donc via cette
 * {@link LifecycleAction} post-login.
 * </p>
 *
 * <p>
 * Mapping appliqué : claim OIDC {@code momo} → custom field {@code user_momo}.
 * Le custom field doit exister au préalable sur le modèle User (sinon on logge
 * un avertissement et on ignore).
 * </p>
 */
@Component(
	immediate = true,
	property = "key=login.events.post",
	service = LifecycleAction.class
)
public class OIDCCustomFieldMapperPostLoginAction implements LifecycleAction {

	@Override
	public void processLifecycleEvent(LifecycleEvent lifecycleEvent)
		throws ActionException {

		HttpServletRequest httpServletRequest = lifecycleEvent.getRequest();

		if (httpServletRequest == null) {
			return;
		}

		try {
			_mapClaimsToCustomFields(httpServletRequest);
		}
		catch (Exception exception) {
			_log.error(
				"Unable to map OIDC claims to user custom fields", exception);
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
				"Cannot decode JWT payload to read OIDC claims: " +
					exception.getMessage());

			return null;
		}
	}

	private void _mapClaimsToCustomFields(
			HttpServletRequest httpServletRequest)
		throws Exception {

		HttpSession httpSession = httpServletRequest.getSession(false);

		if (httpSession == null) {
			return;
		}

		Object sessionAttribute = httpSession.getAttribute(
			OpenIdConnectWebKeys.OPEN_ID_CONNECT_SESSION);

		if (!(sessionAttribute instanceof OpenIdConnectSession)) {

			// Login non-OIDC : rien à mapper.

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

		User user = _fetchUser(httpServletRequest, openIdConnectSession);

		if (user == null) {
			return;
		}

		ExpandoBridge expandoBridge = user.getExpandoBridge();

		for (Map.Entry<String, String> entry :
				_CLAIM_TO_CUSTOM_FIELD.entrySet()) {

			String claimName = entry.getKey();
			String customFieldName = entry.getValue();

			String claimValue = claimsJSONObject.getString(claimName);

			if (Validator.isNull(claimValue)) {
				continue;
			}

			if (!expandoBridge.hasAttribute(customFieldName)) {
				_log.warn(
					"No user custom field named '" + customFieldName +
						"' — create it first (Control Panel > Users > Custom " +
							"Fields). Skipping claim '" + claimName + "'");

				continue;
			}

			// secure=false : écriture en contexte système (pas de contrôle de
			// permission sur le custom field).

			expandoBridge.setAttribute(customFieldName, claimValue, false);

			if (_log.isInfoEnabled()) {
				_log.info(
					"Mapped OIDC claim '" + claimName + "' to custom field '" +
						customFieldName + "' for user " +
							user.getEmailAddress());
			}
		}
	}

	private User _fetchUser(
		HttpServletRequest httpServletRequest,
		OpenIdConnectSession openIdConnectSession) {

		// On évite PortalUtil.getUser(...) qui, par résolution de surcharge,
		// imposerait l'API portlet au classpath de compilation. Le userId est
		// disponible via getRemoteUser() (Liferay y place l'identifiant) ou, à
		// défaut, via la session OIDC.

		long userId = GetterUtil.getLong(httpServletRequest.getRemoteUser());

		if (userId <= 0) {
			userId = openIdConnectSession.getLoginUserId();
		}

		if (userId <= 0) {
			return null;
		}

		return _userLocalService.fetchUser(userId);
	}

	// Mapping claim OIDC -> nom du custom field Liferay. Ajouter d'autres
	// entrées ici pour mapper d'autres claims.
	private static final Map<String, String> _CLAIM_TO_CUSTOM_FIELD =
		new LinkedHashMap<String, String>() {
			{
				put("momo", "user_momo");
			}
		};

	private static final Log _log = LogFactoryUtil.getLog(
		OIDCCustomFieldMapperPostLoginAction.class);

	@Reference
	private UserLocalService _userLocalService;

}
