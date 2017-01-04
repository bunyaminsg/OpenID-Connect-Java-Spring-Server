<%@ page language="java" contentType="text/html; charset=utf-8" pageEncoding="utf-8"%>
<%@ taglib prefix="authz" uri="http://www.springframework.org/security/tags"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@ taglib prefix="o" tagdir="/WEB-INF/tags"%>
<%@ taglib prefix="spring" uri="http://www.springframework.org/tags"%>

<o:header title="Log In" />
<script type="text/javascript">
<!--

$(document).ready(function() {
	// select the appropriate field based on context
	$('#<c:out value="${ login_hint != null ? 'j_password' : 'j_username' }" />').focus();
});

//-->
</script>
<o:topbar />
<div class="container-fluid main">


	<c:if test="${ param.error != null }">
		<div class="alert alert-error"><spring:message code="login.error"/></div>
	</c:if>


	<div class="row-fluid">
		<div class="offset4 span4 well clearfix text-center" style="padding: 50px;">
			<img src="resources/images/openid_connect_large.png" style="display: inline-block; padding: 20px 0px 70px 0px;"/>
			<form action="${ config.issuer }${ config.issuer.endsWith('/') ? '' : '/' }j_spring_security_check" method="POST">
				<div>
					<div class="input-prepend input-block-level">
						<span class="add-on"><i class="icon-user"></i></span>
						<input type="text" placeholder="<spring:message code="login.username"/>" autocorrect="off" autocapitalize="off" autocomplete="off" spellcheck="false" value="<c:out value="${ login_hint }" />" id="j_username" name="j_username">
					</div>
				</div>
				<div>
					<div class="input-prepend input-block-level">
						<span class="add-on"><i class="icon-lock"></i></span>
						<input type="password" placeholder="<spring:message code="login.password"/>" autocorrect="off" autocapitalize="off" autocomplete="off" spellcheck="false" id="j_password" name="j_password">
					</div>
				</div>
				<div>
					<input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
					<input type="submit" class="btn btn-primary span4" style="margin-top: 50px" value="<spring:message code="login.login-button"/>" name="submit">
				</div>
			</form>
		</div>
	</div>
</div>

<o:footer/>
