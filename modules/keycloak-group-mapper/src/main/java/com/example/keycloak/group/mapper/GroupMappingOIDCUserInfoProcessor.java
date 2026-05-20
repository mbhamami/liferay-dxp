package com.example.keycloak.group.mapper;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.model.UserGroup;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.security.sso.openid.connect.OIDCUserInfoProcessor;

import com.nimbusds.openid.connect.sdk.claims.UserInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Lit la claim "groups" envoyée par Keycloak (Group Membership mapper)
 * et synchronise les User Groups Liferay correspondants :
 *  - création des UserGroup manquants,
 *  - assignation à l'utilisateur,
 *  - suppression des UserGroup qui ne sont plus dans la claim.
 */
@Component(
    immediate = true,
    service = OIDCUserInfoProcessor.class
)
public class GroupMappingOIDCUserInfoProcessor
    implements OIDCUserInfoProcessor {

    @Override
    public void processUserInfo(User user, UserInfo userInfo)
        throws PortalException {

        if ((user == null) || (userInfo == null)) {
            return;
        }

        Map<String, Object> claims = userInfo.toJSONObject();
        Object groupsClaim = claims.get(_CLAIM_NAME);

        if (!(groupsClaim instanceof List)) {
            if (_log.isDebugEnabled()) {
                _log.debug(
                    "No 'groups' claim in UserInfo for " +
                        user.getEmailAddress());
            }
            return;
        }

        long companyId = user.getCompanyId();
        List<Long> userGroupIds = new ArrayList<>();

        for (Object g : (List<?>)groupsClaim) {
            String name = String.valueOf(g);

            // Keycloak peut renvoyer "/group" si "Full group path" est activé.
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            if (name.isEmpty()) {
                continue;
            }

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

        _userLocalService.setUserUserGroups(user.getUserId(), ids);

        if (_log.isInfoEnabled()) {
            _log.info(
                "User " + user.getEmailAddress() + " synced with " +
                    ids.length + " UserGroup(s) from Keycloak");
        }
    }

    private static final String _CLAIM_NAME = "groups";

    private static final Log _log = LogFactoryUtil.getLog(
        GroupMappingOIDCUserInfoProcessor.class);

    @Reference
    private UserGroupLocalService _userGroupLocalService;

    @Reference
    private UserLocalService _userLocalService;

}
