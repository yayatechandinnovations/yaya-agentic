import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'y_states.dart';

/// Renders an [AsyncValue] the "fresh by default" way: a first load shows
/// [loading]; a refetch or tenant switch keeps the previous data on screen
/// (dimmed while the context changes) with a thin progress line; an error
/// with data still shows the data.
class LiveAsyncView<T> extends StatelessWidget {
  const LiveAsyncView({
    super.key,
    required this.value,
    required this.data,
    this.onRetry,
    this.loading,
    this.what = 'this list',
  });

  final AsyncValue<T> value;
  final Widget Function(BuildContext context, T data) data;
  final VoidCallback? onRetry;
  final Widget? loading;
  final String what;

  @override
  Widget build(BuildContext context) {
    final busy = value.isLoading && value.hasValue;
    final reloading = value.isReloading;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      SizedBox(
        height: 2,
        child: busy ? const LinearProgressIndicator(minHeight: 2) : null,
      ),
      Expanded(
        child: value.when(
          skipLoadingOnReload: true,
          skipLoadingOnRefresh: true,
          skipError: value.hasValue,
          loading: () => loading ?? const SingleChildScrollView(child: YSkeletonRows()),
          error: (e, _) => YErrorState(error: e, onRetry: onRetry, what: what),
          data: (d) => AnimatedOpacity(
            duration: const Duration(milliseconds: 150),
            opacity: reloading ? 0.45 : 1,
            child: IgnorePointer(ignoring: reloading, child: data(context, d)),
          ),
        ),
      ),
    ]);
  }
}
