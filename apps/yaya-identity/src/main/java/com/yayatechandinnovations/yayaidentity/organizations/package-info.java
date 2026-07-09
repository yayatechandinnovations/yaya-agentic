/**
 * Organizations module (design §8 module 1, §5.1). The org is the tenant
 * boundary — it owns users (via membership), groups, roles, and policies. Maps
 * 1:1 to a yaya-agentic tenant and may own many yaya-sales stores. Also owns
 * membership (a user's tie to an org) and, from I1c, invitations.
 */
package com.yayatechandinnovations.yayaidentity.organizations;
