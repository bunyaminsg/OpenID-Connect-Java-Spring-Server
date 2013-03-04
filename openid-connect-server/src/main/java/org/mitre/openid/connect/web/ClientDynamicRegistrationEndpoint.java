// FIXME: update to latest DynReg spec

package org.mitre.openid.connect.web;

import java.security.Principal;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.mitre.jose.JWEAlgorithmEntity;
import org.mitre.jose.JWEEncryptionMethodEntity;
import org.mitre.jose.JWSAlgorithmEntity;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.ClientDetailsEntity.AppType;
import org.mitre.oauth2.model.ClientDetailsEntity.AuthMethod;
import org.mitre.oauth2.model.ClientDetailsEntity.SubjectType;
import org.mitre.oauth2.model.OAuth2AccessTokenEntity;
import org.mitre.oauth2.model.SystemScope;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.oauth2.service.OAuth2TokenEntityService;
import org.mitre.oauth2.service.SystemScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.provider.DefaultAuthorizationRequest;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

@Controller
@RequestMapping(value = "register"/*, method = RequestMethod.POST*/)
public class ClientDynamicRegistrationEndpoint {

	@Autowired
	private ClientDetailsEntityService clientService;
	
	@Autowired
	private OAuth2TokenEntityService tokenService;

	@Autowired
	private SystemScopeService scopeService;
	
	private JsonParser parser = new JsonParser();
	private Gson gson = new Gson();
	
	@RequestMapping(method = RequestMethod.POST, consumes = "application/json", produces = "application/json")
	public String registerNewClient(@RequestBody String jsonString, Model m, Principal p) {
		
		ClientDetailsEntity newClient = parse(jsonString);
		
		if (newClient != null) {
			// it parsed!
			
			//
			// Now do some post-processing consistency checks on it
			//
			
			// clear out any spurious id/secret (clients don't get to pick)
			newClient.setClientId(null);
			newClient.setClientSecret(null);
			
			// set of scopes that are OK for clients to dynamically register for
			Set<SystemScope> dynScopes = scopeService.getDynReg();

			// scopes that the client is asking for
			Set<SystemScope> requestedScopes = scopeService.fromStrings(newClient.getScope());

			// if the client didn't ask for any, give them the defaults
			if (requestedScopes == null || requestedScopes.isEmpty()) {
				requestedScopes = scopeService.getDefaults();
			}

			// the scopes that the client can have must be a subset of the dynamically allowed scopes
			Set<SystemScope> allowedScopes = Sets.intersection(dynScopes, requestedScopes);

			newClient.setScope(scopeService.toStrings(allowedScopes));
			

			// set default grant types if needed
			if (newClient.getGrantTypes() == null || newClient.getGrantTypes().isEmpty()) { 
				newClient.setGrantTypes(Sets.newHashSet("authorization_code", "refresh_token")); // allow authorization code and refresh token grant types by default
			}
			
			// set default response types if needed
			// TODO: these aren't checked by SECOAUTH
			// TODO: the consistency between the response_type and grant_type needs to be checked by the client service, most likely
			if (newClient.getResponseTypes() == null || newClient.getResponseTypes().isEmpty()) {
				newClient.setResponseTypes(Sets.newHashSet("code")); // default to allowing only the auth code flow
			}
			
			// set some defaults for token timeouts
			newClient.setAccessTokenValiditySeconds((int)TimeUnit.HOURS.toSeconds(1)); // access tokens good for 1hr
			newClient.setIdTokenValiditySeconds((int)TimeUnit.MINUTES.toSeconds(10)); // id tokens good for 10min
			newClient.setRefreshTokenValiditySeconds(null); // refresh tokens good until revoked

			// this client has been dynamically registered (obviously)
			newClient.setDynamicallyRegistered(true);
			
			if (newClient.getTokenEndpointAuthMethod() == null) {
				newClient.setTokenEndpointAuthMethod(AuthMethod.SECRET_BASIC);
			}
			
			if (newClient.getTokenEndpointAuthMethod() == AuthMethod.SECRET_BASIC ||
					newClient.getTokenEndpointAuthMethod() == AuthMethod.SECRET_JWT ||
					newClient.getTokenEndpointAuthMethod() == AuthMethod.SECRET_POST) {
				
				// we need to generate a secret
				newClient = clientService.generateClientSecret(newClient);
			}
			
			
			// now save it
			ClientDetailsEntity savedClient = clientService.saveNewClient(newClient);
			
			// generate the registration access token
			OAuth2AccessTokenEntity token = createRegistrationAccessToken(savedClient);
			
			// send it all out to the view
			m.addAttribute("client", savedClient);
			m.addAttribute("code", HttpStatus.CREATED); // http 201
			m.addAttribute("token", token);
			
			return "clientInformationResponseView";
		} else {
			// didn't parse, this is a bad request
			
			m.addAttribute("code", HttpStatus.BAD_REQUEST);
			
			return "httpCodeView";
		}
		
	}

	/**
	 * 
	 * Create an unbound ClientDetailsEntity from the given JSON string.
	 * 
	 * @param jsonString
	 * @return the entity if successful, null otherwise
	 */
    private ClientDetailsEntity parse(String jsonString) {
		JsonElement jsonEl = parser.parse(jsonString);
		if (jsonEl.isJsonObject()) {

			JsonObject o = jsonEl.getAsJsonObject();
			ClientDetailsEntity c = new ClientDetailsEntity();
			
			// TODO: make these field names into constants
			
			// OAuth DynReg
			c.setRedirectUris(getAsStringSet(o, "redirect_uris"));
			c.setClientName(getAsString(o, "client_name"));
			c.setClientUri(getAsString(o, "client_uri"));
			c.setLogoUri(getAsString(o, "logo_uri"));
			c.setContacts(getAsStringSet(o, "contacts"));
			c.setTosUri(getAsString(o, "tos_uri"));
			
			String authMethod = getAsString(o, "token_endpoint_auth_method");
			if (authMethod != null) {
				c.setTokenEndpointAuthMethod(AuthMethod.getByValue(authMethod));
			}
			
			// scope is a space-separated string
			String scope = getAsString(o, "scope");
			if (scope != null) {
				c.setScope(Sets.newHashSet(Splitter.on(" ").split(scope)));
			}
			
			c.setGrantTypes(getAsStringSet(o, "grant_type"));
			c.setPolicyUri(getAsString(o, "policy_uri"));
			c.setJwksUri(getAsString(o, "jwks_uri"));
			
			
			// OIDC Additions
			String appType = getAsString(o, "application_type");
			if (appType != null) {
				c.setApplicationType(AppType.getByValue(appType));
			}
			
			c.setSectorIdentifierUri(getAsString(o, "sector_identifier_uri"));
			
			String subjectType = getAsString(o, "subject_type");
			if (subjectType != null) {
				c.setSubjectType(SubjectType.getByValue(subjectType));
			}
			
			c.setRequestObjectSigningAlg(getAsJwsAlgorithm(o, "request_object_signing_alg"));
			
			c.setUserInfoSignedResponseAlg(getAsJwsAlgorithm(o, "userinfo_signed_response_alg"));
			c.setUserInfoEncryptedResponseAlg(getAsJweAlgorithm(o, "userinfo_encrypted_response_alg"));
			c.setUserInfoEncryptedResponseEnc(getAsJweEncryptionMethod(o, "userinfo_encrypted_response_enc"));
			
			c.setIdTokenSignedResponseAlg(getAsJwsAlgorithm(o, "id_token_signed_response_alg"));
			c.setIdTokenEncryptedResponseAlg(getAsJweAlgorithm(o, "id_token_encrypted_response_alg"));
			c.setIdTokenEncryptedReponseEnc(getAsJweEncryptionMethod(o, "id_token_encrypted_response_enc"));
			
			if (o.has("default_max_age")) {
				if (o.get("default_max_age").isJsonPrimitive()) {
					c.setDefaultMaxAge(o.get("default_max_age").getAsInt());
				}
			}
			
			if (o.has("require_auth_time")) {
				if (o.get("require_auth_time").isJsonPrimitive()) {
					c.setRequireAuthTime(o.get("require_auth_time").getAsBoolean());
				}
			}
			
			c.setDefaultACRvalues(getAsStringSet(o, "default_acr_values"));
			c.setInitiateLoginUri(getAsString(o, "initiate_login_uri"));
			c.setPostLogoutRedirectUri(getAsString(o, "post_logout_redirect_uri"));
			c.setRequestUris(getAsStringSet(o, "request_uris"));
			
			return c;
		} else {
	    	return null;
		}
    }

	/**
	 * Gets the value of the given given member as a set of strings, null if it doesn't exist
	 */
    private Set<String> getAsStringSet(JsonObject o, String member) throws JsonSyntaxException {
    	if (o.has(member)) {
    		return gson.fromJson(o.get(member), new TypeToken<Set<String>>(){}.getType());
    	} else {
    		return null;
    	}
    }
    
    /**
     * Gets the value of the given member as a string, null if it doesn't exist
     */
    private String getAsString(JsonObject o, String member) {
    	if (o.has(member)) {
    		JsonElement e = o.get(member);
    		if (e != null && e.isJsonPrimitive()) {
    			return e.getAsString();
    		} else {
    			return null;
    		}
    	} else {
    		return null;
    	}
    }
    
    /**
     * Gets the value of the given member as a JWS Algorithm, null if it doesn't exist
     */
    private JWSAlgorithmEntity getAsJwsAlgorithm(JsonObject o, String member) {
    	String s = getAsString(o, member);
    	if (s != null) {
    		return new JWSAlgorithmEntity(s);
    	} else {
    		return null;
    	}
    }

    /**
     * Gets the value of the given member as a JWE Algorithm, null if it doesn't exist
     */
    private JWEAlgorithmEntity getAsJweAlgorithm(JsonObject o, String member) {
    	String s = getAsString(o, member);
    	if (s != null) {
    		return new JWEAlgorithmEntity(s);
    	} else {
    		return null;
    	}
    }
    

    /**
     * Gets the value of the given member as a JWE Encryption Method, null if it doesn't exist
     */
    private JWEEncryptionMethodEntity getAsJweEncryptionMethod(JsonObject o, String member) {
    	String s = getAsString(o, member);
    	if (s != null) {
    		return new JWEEncryptionMethodEntity(s);
    	} else {
    		return null;
    	}
    }
	/**
     * @param client
     * @return
     * @throws AuthenticationException
     */
    private OAuth2AccessTokenEntity createRegistrationAccessToken(ClientDetailsEntity client) throws AuthenticationException {
	    // create a registration access token, treat it like a client credentials flow
		// I can't use the auth request interface here because it has no setters and bad constructors -- THIS IS BAD API DESIGN
		DefaultAuthorizationRequest authorizationRequest = new DefaultAuthorizationRequest(client.getClientId(), Sets.newHashSet(OAuth2AccessTokenEntity.REGISTRATION_TOKEN_SCOPE));
		authorizationRequest.setApproved(true);
		authorizationRequest.setAuthorities(Sets.newHashSet(new SimpleGrantedAuthority("ROLE_CLIENT")));
		OAuth2Authentication authentication = new OAuth2Authentication(authorizationRequest, null);
		OAuth2AccessTokenEntity registrationAccessToken = (OAuth2AccessTokenEntity) tokenService.createAccessToken(authentication);
	    return registrationAccessToken;
    }
	
}
