import 'package:dio/dio.dart';
import 'package:get_it/get_it.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/data/datasources/auth_remote_data_source.dart';
import '../../features/auth/data/platform/oauth_launcher.dart';
import '../../features/auth/data/platform/web_oauth_launcher.dart';
import '../../features/auth/data/repositories/auth_repository_impl.dart';
import '../../features/auth/domain/repositories/auth_repository.dart';
import '../../features/auth/domain/usecases/auth_usecases.dart';
import '../../features/auth/presentation/bloc/auth_bloc.dart';
import '../../features/orgs/data/datasources/org_remote_data_source.dart';
import '../../features/orgs/data/repositories/org_repository_impl.dart';
import '../../features/orgs/domain/repositories/org_repository.dart';
import '../../features/orgs/domain/usecases/org_usecases.dart';
import '../../features/orgs/presentation/bloc/orgs_bloc.dart';
import '../../features/audit/data/datasources/audit_remote_data_source.dart';
import '../../features/audit/data/repositories/audit_repository_impl.dart';
import '../../features/audit/domain/repositories/audit_repository.dart';
import '../../features/audit/domain/usecases/read_audit.dart';
import '../../features/audit/presentation/bloc/audit_bloc.dart';
import '../../features/clients/data/datasources/client_remote_data_source.dart';
import '../../features/clients/data/repositories/client_repository_impl.dart';
import '../../features/clients/domain/repositories/client_repository.dart';
import '../../features/clients/domain/usecases/client_usecases.dart';
import '../../features/clients/presentation/bloc/clients_bloc.dart';
import '../../features/groups/data/datasources/group_remote_data_source.dart';
import '../../features/groups/data/repositories/group_repository_impl.dart';
import '../../features/groups/domain/repositories/group_repository.dart';
import '../../features/groups/domain/usecases/group_usecases.dart';
import '../../features/groups/presentation/bloc/groups_bloc.dart';
import '../../features/policies/data/datasources/policy_admin_data_source.dart';
import '../../features/policies/data/repositories/policy_admin_repository_impl.dart';
import '../../features/policies/domain/repositories/policy_admin_repository.dart';
import '../../features/policies/domain/usecases/policy_admin_usecases.dart';
import '../../features/policies/presentation/bloc/policy_editor_bloc.dart';
import '../../features/signup/data/datasources/signup_remote_data_source.dart';
import '../../features/signup/data/repositories/signup_repository_impl.dart';
import '../../features/signup/domain/repositories/signup_repository.dart';
import '../../features/signup/domain/usecases/signup_usecases.dart';
import '../../features/signup/presentation/bloc/accept_invite_bloc.dart';
import '../../features/signup/presentation/bloc/signup_bloc.dart';
import '../../features/settings/data/datasources/settings_data_source.dart';
import '../../features/settings/data/repositories/settings_repository_impl.dart';
import '../../features/settings/domain/repositories/settings_repository.dart';
import '../../features/settings/domain/usecases/settings_usecases.dart';
import '../../features/settings/presentation/bloc/settings_bloc.dart';
import '../../features/users/data/datasources/user_remote_data_source.dart';
import '../../features/users/data/repositories/user_repository_impl.dart';
import '../../features/users/domain/repositories/user_repository.dart';
import '../../features/users/domain/usecases/user_usecases.dart';
import '../../features/users/presentation/bloc/users_bloc.dart';
import '../../features/policies/data/datasources/policy_remote_data_source.dart';
import '../../features/policies/data/repositories/policy_repository_impl.dart';
import '../../features/policies/domain/repositories/policy_repository.dart';
import '../../features/policies/domain/usecases/simulate_decision.dart';
import '../../features/policies/presentation/bloc/simulator_bloc.dart';
import '../../features/roles/data/datasources/role_remote_data_source.dart';
import '../../features/roles/data/repositories/role_repository_impl.dart';
import '../../features/roles/domain/repositories/role_repository.dart';
import '../../features/roles/domain/usecases/role_usecases.dart';
import '../../features/roles/presentation/bloc/roles_bloc.dart';
import '../config/app_config.dart';
import '../context/org_context_bloc.dart';
import '../network/dio_client.dart';
import '../storage/token_store.dart';

final GetIt sl = GetIt.instance;

/// The composition root — the only place concrete implementations are wired to
/// interfaces. Everything else depends on abstractions.
Future<void> configureDependencies() async {
  const config = AppConfig.fromEnv;
  final prefs = await SharedPreferences.getInstance();

  sl.registerSingleton<AppConfig>(config);
  sl.registerSingleton<TokenStore>(SharedPrefsTokenStore(prefs));
  sl.registerSingleton<Dio>(DioClient.build(config, sl<TokenStore>()));
  sl.registerSingleton<OAuthLauncher>(WebOAuthLauncher());

  // --- auth ---
  sl.registerSingleton(AuthRemoteDataSource(sl<Dio>(), config));
  sl.registerSingleton<AuthRepository>(AuthRepositoryImpl(
    remote: sl(),
    tokens: sl(),
    launcher: sl(),
    config: config,
  ));
  sl.registerLazySingleton(() => AuthBloc(
        loadSession: LoadSession(sl()),
        beginLogin: BeginLogin(sl()),
        completeLogin: CompleteLogin(sl()),
        logout: Logout(sl()),
      ));

  // --- orgs ---
  sl.registerSingleton(OrgRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<OrgRepository>(OrgRepositoryImpl(sl()));
  sl.registerFactory(() => OrgsBloc(
        listOrgs: ListOrgs(sl()),
        createOrg: CreateOrg(sl()),
        updateOrg: UpdateOrg(sl()),
      ));

  // --- org scope (console-wide picker) ---
  sl.registerLazySingleton(() => OrgContextBloc(listOrgs: ListOrgs(sl()), prefs: prefs));

  // --- policies ---
  sl.registerSingleton(PolicyRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<PolicyRepository>(PolicyRepositoryImpl(sl()));
  sl.registerFactory(() => SimulatorBloc(
        simulate: SimulateDecision(sl()),
        listMembers: ListMembers(sl()),
        getPermissionCatalog: GetPermissionCatalog(sl()),
      ));

  // --- roles ---
  sl.registerSingleton(RoleRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<RoleRepository>(RoleRepositoryImpl(sl()));
  sl.registerFactory(() => RolesBloc(
        listRoles: ListRoles(sl()),
        createRole: CreateRole(sl()),
        assignRole: AssignRole(sl()),
        getPermissionCatalog: GetPermissionCatalog(sl()),
        listMembers: ListMembers(sl()),
        listGroups: ListGroups(sl()),
      ));

  // --- audit ---
  sl.registerSingleton(AuditRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<AuditRepository>(AuditRepositoryImpl(sl()));
  sl.registerFactory(() => AuditBloc(readAudit: ReadAudit(sl())));

  // --- users ---
  sl.registerSingleton(UserRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<UserRepository>(UserRepositoryImpl(sl()));
  sl.registerFactory(() => UsersBloc(
        listMembers: ListMembers(sl()),
        lockUser: LockUser(sl()),
        disableUser: DisableUser(sl()),
        deleteUser: DeleteUser(sl()),
        inviteUser: InviteUser(sl()),
        listRoles: ListRoles(sl()),
      ));

  // --- clients ---
  sl.registerSingleton(ClientRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<ClientRepository>(ClientRepositoryImpl(sl()));
  sl.registerFactory(() => ClientsBloc(
        listClients: ListClients(sl()),
        registerServiceAccount: RegisterServiceAccount(sl()),
        rotateClientSecret: RotateClientSecret(sl()),
      ));

  // --- groups ---
  sl.registerSingleton(GroupRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<GroupRepository>(GroupRepositoryImpl(sl()));
  sl.registerFactory(() => GroupsBloc(
        listGroups: ListGroups(sl()),
        createGroup: CreateGroup(sl()),
        listGroupMembers: ListGroupMembers(sl()),
        addGroupMember: AddGroupMember(sl()),
        removeGroupMember: RemoveGroupMember(sl()),
        listMembers: ListMembers(sl()),
      ));

  // --- policy editor ---
  sl.registerSingleton(PolicyAdminDataSource(sl<Dio>()));
  sl.registerSingleton<PolicyAdminRepository>(PolicyAdminRepositoryImpl(sl()));
  sl.registerFactory(() => PolicyEditorBloc(
        listPolicies: ListPolicies(sl()),
        createPolicy: CreatePolicy(sl()),
        getPermissionCatalog: GetPermissionCatalog(sl()),
      ));

  // --- signup (public onboarding) ---
  sl.registerSingleton(SignupRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<SignupRepository>(SignupRepositoryImpl(sl()));
  sl.registerFactory(() => SignupBloc(
        registerSignup: RegisterSignup(sl()),
        verifySignup: VerifySignup(sl()),
        resendSignupOtp: ResendSignupOtp(sl()),
      ));
  sl.registerFactory(() => AcceptInviteBloc(acceptInvitation: AcceptInvitation(sl())));

  // --- settings ---
  sl.registerSingleton(SettingsDataSource(sl<Dio>()));
  sl.registerSingleton<SettingsRepository>(SettingsRepositoryImpl(sl()));
  sl.registerFactory(() => SettingsBloc(
        loadOrgSettings: LoadOrgSettings(sl()),
        setMfaRequired: SetMfaRequired(sl()),
        rotateKeys: RotateKeys(sl()),
      ));
}
