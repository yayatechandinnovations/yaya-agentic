import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../context/org_context_bloc.dart';
import '../theme/app_theme.dart';

/// The AppBar organization switcher: shows the current org scope by NAME and
/// lets the operator switch. Lives in every org-scoped page's actions so no
/// screen ever asks for a raw org id.
class OrgScopePicker extends StatelessWidget {
  const OrgScopePicker({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<OrgContextBloc, OrgContextState>(
      builder: (context, state) {
        if (state.status == OrgContextStatus.initial) {
          context.read<OrgContextBloc>().add(const OrgContextRequested());
        }
        if (state.orgs.isEmpty) return const SizedBox.shrink();
        return Padding(
          padding: const EdgeInsets.only(right: 16),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: const Color(0xFFF3F4FA),
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: const Color(0xFFE3E4F0)),
            ),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<String>(
                value: state.selectedOrgId,
                icon: const Icon(Icons.unfold_more, size: 18),
                borderRadius: BorderRadius.circular(10),
                items: state.orgs
                    .map((o) => DropdownMenuItem(
                          value: o.id,
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Icon(Icons.apartment, size: 16, color: AppBrand.violet),
                              const SizedBox(width: 8),
                              Text(o.name, style: const TextStyle(fontSize: 14)),
                            ],
                          ),
                        ))
                    .toList(),
                onChanged: (id) {
                  if (id != null) {
                    context.read<OrgContextBloc>().add(OrgContextSelected(id));
                  }
                },
              ),
            ),
          ),
        );
      },
    );
  }
}
