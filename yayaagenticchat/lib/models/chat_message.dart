/// One bubble in the conversation. Assistant bubbles accrete streamed tokens and
/// may carry engine-emitted quick replies + citations (chat-channel-design.md §9.1).
class ChatMessage {
  final String role; // 'user' | 'assistant'
  final String text;
  final List<String> quickReplies;
  final List<Citation> citations;
  final bool streaming;

  const ChatMessage({
    required this.role,
    required this.text,
    this.quickReplies = const [],
    this.citations = const [],
    this.streaming = false,
  });

  bool get isUser => role == 'user';

  ChatMessage copyWith({
    String? text,
    List<String>? quickReplies,
    List<Citation>? citations,
    bool? streaming,
  }) =>
      ChatMessage(
        role: role,
        text: text ?? this.text,
        quickReplies: quickReplies ?? this.quickReplies,
        citations: citations ?? this.citations,
        streaming: streaming ?? this.streaming,
      );
}

class Citation {
  final String title;
  final String? url;
  const Citation({required this.title, this.url});
}
