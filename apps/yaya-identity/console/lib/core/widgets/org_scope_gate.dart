import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../context/org_context_bloc.dart';

/// Wraps an org-scoped page body: resolves the current org scope, calls [onOrg]
/// when it becomes available or changes (so the page can load its data), and
/// renders the empty/loading states until then.
class OrgScopeGate extends StatefulWidget {
  const OrgScopeGate({super.key, required this.onOrg, required this.builder});

  /// Dispatch the feature's load event. Called once when the scope is first
  /// resolved and again whenever the operator switches org.
  final void Function(BuildContext context, String orgId) onOrg;

  final Widget Function(BuildContext context, String orgId) builder;

  @override
  State<OrgScopeGate> createState() => _OrgScopeGateState();
}

class _OrgScopeGateState extends State<OrgScopeGate> {
  String? _notifiedOrgId;

  @override
  void initState() {
    super.initState();
    final bloc = context.read<OrgContextBloc>();
    if (bloc.state.status == OrgContextStatus.initial) {
      bloc.add(const OrgContextRequested());
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _notifyIfResolved(bloc.state);
    });
  }

  void _notifyIfResolved(OrgContextState state) {
    final orgId = state.selectedOrgId;
    if (orgId != null && orgId != _notifiedOrgId) {
      _notifiedOrgId = orgId;
      widget.onOrg(context, orgId);
    }
  }

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<OrgContextBloc, OrgContextState>(
      listenWhen: (p, c) => p.selectedOrgId != c.selectedOrgId,
      listener: (context, state) => _notifyIfResolved(state),
      builder: (context, state) {
        switch (state.status) {
          case OrgContextStatus.initial:
          case OrgContextStatus.loading:
            return const Center(child: CircularProgressIndicator());
          case OrgContextStatus.failure:
            return Center(
              child: Semantics(
                liveRegion: true,
                child: Text(state.error ?? 'Could not load organizations'),
              ),
            );
          case OrgContextStatus.loaded:
            final orgId = state.selectedOrgId;
            if (orgId == null) {
              return Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.apartment, size: 48, color: Colors.black26),
                    const SizedBox(height: 12),
                    const Text('No organizations yet.'),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: () => context.go('/orgs'),
                      icon: const Icon(Icons.add),
                      label: const Text('Create one'),
                    ),
                  ],
                ),
              );
            }
            return widget.builder(context, orgId);
        }
      },
    );
  }
}
