/// Opt-in starter templates for the flow builder. Each returns a fully-populated
/// [FlowDraft] so the graphical editor opens pre-filled — the operator tweaks
/// from there. Shown only when the user chooses "Start from a template".
library;

import 'flow_drafts.dart';

typedef FlowTemplate = FlowDraft Function();

final Map<String, FlowTemplate> kFlowTemplates = {
  'Returning-customer recognition (assistant greeting)': _returningCustomer,
  'Phone verification (OTP)': _phoneOtp,
};

FlowDraft _returningCustomer() => FlowDraft(
      id: 'returning-customer',
      name: 'Returning customer recognition',
      description: 'Look the user up by phone and greet a recognized customer.',
      steps: [
        RunToolDraft(
          'lookup',
          toolId: 'account-lookup',
          inputs: [
            InputDraft(argName: 'phone', source: ValueSourceDraft(kind: 'Channel', channelField: 'endUserPhone')),
          ],
          captures: [
            CaptureDraft(jsonPointer: '/name', targetKind: 'Entity', name: 'customerName'),
          ],
          next: [
            BranchDraft(
              left: ValueSourceDraft(kind: 'StepOutput', stepKey: 'lookup', jsonPointer: '/found'),
              op: 'EQ',
              right: 'true',
              targetKind: 'End',
              outcomeLabel: 'success',
            ),
          ],
        ),
      ],
      greeting: GreetingDraft(
        mode: 'LLM_COMPOSED',
        successTemplate: 'Welcome back, {customerName}!',
        fallbackGreeting: 'Hi! How can I help you today?',
        quickReplies: ['Track my order'],
        instruction: 'Greet the returning customer warmly by name and offer to help — one sentence.',
        briefingEntities: ['customerName'],
      ),
    );

FlowDraft _phoneOtp() => FlowDraft(
      id: 'phone-otp',
      name: 'Phone verification',
      description: 'Recognize the customer, ask for the code, verify it.',
      steps: [
        RunToolDraft(
          'lookup',
          toolId: 'account-lookup',
          captures: [CaptureDraft(jsonPointer: '/name', targetKind: 'Entity', name: 'customerName')],
        ),
        ElicitDraft(
          'otp',
          promptTemplate: 'Enter the code we texted you, {customerName}',
          uiHintKind: 'otp_entry',
          captureKind: 'StepVar',
          captureName: 'code',
        ),
        RunToolDraft(
          'verify',
          toolId: 'verify-otp',
          inputs: [
            InputDraft(
                argName: 'code',
                source: ValueSourceDraft(kind: 'Entity', entityName: 'code'),
                mode: 'REQUIRED'),
          ],
          captures: [CaptureDraft(jsonPointer: '/verified', targetKind: 'Entity', name: 'phoneVerified')],
        ),
      ],
      greeting: GreetingDraft(
        mode: 'STATIC_TEMPLATE',
        successTemplate: "You're verified, {customerName}!",
        fallbackGreeting: 'Hi! How can I help?',
      ),
    );
