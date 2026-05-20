package com.example.keycloak.group.mapper;

import com.liferay.portal.kernel.events.ActionException;
import com.liferay.portal.kernel.events.LifecycleAction;
import com.liferay.portal.kernel.events.LifecycleEvent;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.model.UserGroup;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.security.sso.openid.connect.OpenIdConnectSession;
import com.liferay.portal.security.sso.openid.connect.constants.OpenIdConnectWebKeys;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Post-login action : déclenchée après chaque login Liferay. Si l'utilisateur
 * s'est authentifié via OpenID Connect, récupère l'access token depuis la
 * session OIDC, décode la claim "groups" du JWT, et synchronise les UserGroups
 * Liferay :
 *  - crée les UserGroup manquants ;
 *  - assigne l'utilisateur aux UserGroup ;
 *  - retire les UserGroup qui ne sont plus dans la claim.
 *
 * Ne fait rien si le login n'est pas OIDC (mot de passe local, etc.).
 */
@Component(
    immediate = true,
    property = "key=login.events.post",
    service = LifecycleAction.class
)
public class KeycloakGroupSyncPostLoginAction implements LifecycleAction {

    @Override
    public void processLifecycleEvent(LifecycleEvent lifecycleEvent)
        throws ActionException {

        HttpServletRequest httpServletRequest = lifecycleEvent.getRequest();

        if (httpServletRequest == null) {
            return;
        }

        try {
            _process(httpServletRequest);
        }
        catch (Exception exception) {
            _log.error(
                "Failed to sync Keycloak groups for current login",
                exception);
        }
    }

    private void _process(HttpServletRequest httpServletRequest)
        throws Exception {

        HttpSession httpSession = httpServletRequest.getSession(false);

        if (httpSession == null) {
            return;
        }

        Object sessionAttribute = httpSession.getAttribute(
            OpenIdConnectWebKeys.OPEN_ID_CONNECT_SESSION);

        if (!(sessionAttribute instanceof OpenIdConnectSession)) {

            // Login non-OIDC : rien à faire.

            return;
        }

        OpenIdConnectSession openIdConnectSession =
            (OpenIdConnectSession)sessionAttribute;

        String accessTokenValue = openIdConnectSession.getAccessTokenValue();

        if ((accessTokenValue == null) || accessTokenValue.isEmpty()) {
            return;
        }

        List<String> groups = _extractGroupsClaim(accessTokenValue);

        if (groups.isEmpty()) {
            return;
        }

        User user = PortalUtil.getUser(httpServletRequest);

        if (user == null) {
            return;
        }

        _syncUserGroups(user, groups);
    }

    private List<String> _extractGroupsClaim(String jwt) {
        String[] parts = jwt.split("\\.");

        if (parts.length < 2) {
            return Collections.emptyList();
        }

        String payload = new String(
            Base64.getUrlDecoder().decode(parts[1]),
            StandardCharsets.UTF_8);

        try {
            JSONObject jsonObject = JSONFactoryUtil.createJSONObject(payload);

            JSONArray groupsArray = jsonObject.getJSONArray("groups");

            if (groupsArray == null) {
                return Collections.emptyList();
            }

            List<String> result = new ArrayList<>(groupsArray.length());

            for (int i = 0; i < groupsArray.length(); i++) {
                String name = groupsArray.getString(i);

                if (name.startsWith("/")) {
                    name = name.substring(1);
                }

                if (!name.isEmpty()) {
                    result.add(name);
                }
            }

            return result;
        }
        catch (Exception exception) {
            _log.warn(
                "Cannot decode JWT payload to extract 'groups' claim: " +
                    exception.getMessage());

            return Collections.emptyList();
        }
    }

    private void _syncUserGroups(User user, List<String> groups)
        throws Exception {

        long companyId = user.getCompanyId();
        List<Long> userGroupIds = new ArrayList<>(groups.size());

        for (String name : groups) {
            UserGroup userGroup = _userGroupLocalService.fetchUserGroup(
                companyId, name);

            if (userGroup == null) {
                try {
                    ServiceContext serviceContext = new ServiceContext();

                    userGroup = _userGroupLocalService.addUserGroup(
                        user.getUserId(), companyId, name,
                        "Auto-created from Keycloak claim 'groups'",
                        serviceContext);

                    _log.info(
                        "Created Liferay UserGroup '" + name +
                            "' from Keycloak claim");
                }
                catch (Exception exception) {
                    _log.warn(
                        "Cannot create UserGroup '" + name + "': " +
                            exception.getMessage());

                    continue;
                }
            }

            userGroupIds.add(userGroup.getUserGroupId());
        }

        long[] ids = userGroupIds.stream(
        ).mapToLong(
            Long::longValue
        ).toArray();

        _userGroupLocalService.setUserUserGroups(user.getUserId(), ids);

        if (_log.isInfoEnabled()) {
            _log.info(
                "User " + user.getEmailAddress() + " synced with " +
                    ids.length + " UserGroup(s) from Keycloak");
        }
    }

    private static final Log _log = LogFactoryUtil.getLog(
        KeycloakGroupSyncPostLoginAction.class);

    @Reference
    private UserGroupLocalService _userGroupLocalService;

}
