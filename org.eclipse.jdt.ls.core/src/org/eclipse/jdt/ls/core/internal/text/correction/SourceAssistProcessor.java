/*******************************************************************************
* Copyright (c) 2017-2021 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License 2.0
* which accompanies this distribution, and is available at
* https://www.eclipse.org/legal/epl-2.0/
*
* SPDX-License-Identifier: EPL-2.0
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package org.eclipse.jdt.ls.core.internal.text.correction;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.manipulation.CoreASTProvider;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.fix.IProposableFix;
import org.eclipse.jdt.internal.corext.fix.VariableDeclarationFixCore;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.internal.ui.text.correction.IProblemLocationCore;
import org.eclipse.jdt.ls.core.internal.ChangeUtil;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaCodeActionKind;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.TextEditConverter;
import org.eclipse.jdt.ls.core.internal.codemanipulation.GenerateGetterSetterOperation;
import org.eclipse.jdt.ls.core.internal.codemanipulation.GenerateGetterSetterOperation.AccessorField;
import org.eclipse.jdt.ls.core.internal.corrections.CorrectionMessages;
import org.eclipse.jdt.ls.core.internal.corrections.DiagnosticsHelper;
import org.eclipse.jdt.ls.core.internal.corrections.IInvocationContext;
import org.eclipse.jdt.ls.core.internal.corrections.InnovationContext;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.FixCorrectionProposal;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.IProposalRelevance;
import org.eclipse.jdt.ls.core.internal.handlers.CodeActionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.CodeActionProposal;
import org.eclipse.jdt.ls.core.internal.handlers.CodeGenerationUtils;
import org.eclipse.jdt.ls.core.internal.handlers.GenerateConstructorsHandler;
import org.eclipse.jdt.ls.core.internal.handlers.GenerateConstructorsHandler.CheckConstructorsResponse;
import org.eclipse.jdt.ls.core.internal.handlers.GenerateDelegateMethodsHandler;
import org.eclipse.jdt.ls.core.internal.handlers.GenerateToStringHandler;
import org.eclipse.jdt.ls.core.internal.handlers.JdtDomModels.LspVariableBinding;
import org.eclipse.jdt.ls.core.internal.handlers.OrganizeImportsHandler;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.text.edits.TextEdit;

import com.google.common.collect.Sets;

public class SourceAssistProcessor {

	private static final Set<String> UNSUPPORTED_RESOURCES = Sets.newHashSet("module-info.java", "package-info.java");

	public static final String COMMAND_ID_ACTION_OVERRIDEMETHODSPROMPT = "java.action.overrideMethodsPrompt";
	public static final String COMMAND_ID_ACTION_HASHCODEEQUALSPROMPT = "java.action.hashCodeEqualsPrompt";
	public static final String COMMAND_ID_ACTION_ORGANIZEIMPORTS = "java.action.organizeImports";
	public static final String COMMAND_ID_ACTION_GENERATETOSTRINGPROMPT = "java.action.generateToStringPrompt";
	public static final String COMMAND_ID_ACTION_GENERATEACCESSORSPROMPT = "java.action.generateAccessorsPrompt";
	public static final String COMMAND_ID_ACTION_GENERATECONSTRUCTORSPROMPT = "java.action.generateConstructorsPrompt";
	public static final String COMMAND_ID_ACTION_GENERATEDELEGATEMETHODSPROMPT = "java.action.generateDelegateMethodsPrompt";

	private PreferenceManager preferenceManager;

	public SourceAssistProcessor(PreferenceManager preferenceManager) {
		this.preferenceManager = preferenceManager;
	}

	public List<Either<Command, CodeAction>> getSourceActionCommands(CodeActionParams params, IInvocationContext context, IProblemLocationCore[] locations, IProgressMonitor monitor) {
		List<Either<Command, CodeAction>> $ = new ArrayList<>();
		ICompilationUnit cu = context.getCompilationUnit();
		IType type = getSelectionType(context);
		boolean isInTypeDeclaration = isInTypeDeclaration(context);

		// Generate Constructor quickassist
		Optional<Either<Command, CodeAction>> generateConstructors = null;
		try {
			IJavaElement element = JDTUtils.findElementAtSelection(cu, params.getRange().getEnd().getLine(), params.getRange().getEnd().getCharacter(), this.preferenceManager, new NullProgressMonitor());
			if (element instanceof IField) {
				generateConstructors = getGenerateConstructorsAction(params, context, type, JavaCodeActionKind.QUICK_ASSIST, monitor);
				addSourceActionCommand($, params.getContext(), generateConstructors);
			}
		} catch (JavaModelException e) {
			JavaLanguageServerPlugin.logException(e);
		}

		// Organize Imports
		if (preferenceManager.getClientPreferences().isAdvancedOrganizeImportsSupported()) {
			Optional<Either<Command, CodeAction>> organizeImports = getOrganizeImportsAction(params);
			addSourceActionCommand($, params.getContext(), organizeImports);
		} else {
			CodeActionProposal organizeImportsProposal = (pm) -> {
				TextEdit edit = getOrganizeImportsProposal(context, pm);
				return convertToWorkspaceEdit(cu, edit);
			};
			Optional<Either<Command, CodeAction>> organizeImports = getCodeActionFromProposal(params.getContext(), context.getCompilationUnit(), CorrectionMessages.ReorgCorrectionsSubProcessor_organizeimports_description,
					CodeActionKind.SourceOrganizeImports, organizeImportsProposal);
			addSourceActionCommand($, params.getContext(), organizeImports);
		}

		if (!UNSUPPORTED_RESOURCES.contains(cu.getResource().getName())) {
			// Override/Implement Methods
			Optional<Either<Command, CodeAction>> overrideMethods = getOverrideMethodsAction(params);
			addSourceActionCommand($, params.getContext(), overrideMethods);
		}

		// Generate Getter and Setter QuickAssist
		if (isInTypeDeclaration) {
			Optional<Either<Command, CodeAction>> quickAssistGetterSetter = getGetterSetterAction(params, context, type, JavaCodeActionKind.QUICK_ASSIST, isInTypeDeclaration);
			addSourceActionCommand($, params.getContext(), quickAssistGetterSetter);
		}
		// Generate Getter and Setter Source Action
		Optional<Either<Command, CodeAction>> sourceGetterSetter = getGetterSetterAction(params, context, type, JavaCodeActionKind.SOURCE_GENERATE_ACCESSORS, isInTypeDeclaration);
		addSourceActionCommand($, params.getContext(), sourceGetterSetter);

		// Generate hashCode() and equals()
		if (supportsHashCodeEquals(context, type, monitor)) {
			// Generate QuickAssist
			if (isInTypeDeclaration) {
				Optional<Either<Command, CodeAction>> quickAssistHashCodeEquals = getHashCodeEqualsAction(params, JavaCodeActionKind.QUICK_ASSIST);
				addSourceActionCommand($, params.getContext(), quickAssistHashCodeEquals);
			}

			// Generate Source Action
			Optional<Either<Command, CodeAction>> sourceActionHashCodeEquals = getHashCodeEqualsAction(params, JavaCodeActionKind.SOURCE_GENERATE_HASHCODE_EQUALS);
			addSourceActionCommand($, params.getContext(), sourceActionHashCodeEquals);

		}

		// Generate toString()
		if (supportsGenerateToString(type)) {
			boolean nonStaticFields = true;
			try {
				nonStaticFields = hasFields(type, false);
			} catch (JavaModelException e) {
				// do nothing.
			}
			if (nonStaticFields) {
				// Generate QuickAssist
				if (isInTypeDeclaration) {
					Optional<Either<Command, CodeAction>> generateToStringQuickAssist = getGenerateToStringAction(params, JavaCodeActionKind.QUICK_ASSIST);
					addSourceActionCommand($, params.getContext(), generateToStringQuickAssist);
				}
				// Generate Source Action
				Optional<Either<Command, CodeAction>> generateToStringCommand = getGenerateToStringAction(params, JavaCodeActionKind.SOURCE_GENERATE_TO_STRING);
				addSourceActionCommand($, params.getContext(), generateToStringCommand);
			} else {
				CodeActionProposal generateToStringProposal = (pm) -> {
					IJavaElement insertPosition = isInTypeDeclaration ? CodeGenerationUtils.findInsertElement(type, null) : CodeGenerationUtils.findInsertElement(type, context.getSelectionOffset());
					TextEdit edit = GenerateToStringHandler.generateToString(type, new LspVariableBinding[0], insertPosition, pm);
					return convertToWorkspaceEdit(cu, edit);
				};
				// Generate QuickAssist
				if (isInTypeDeclaration) {
					Optional<Either<Command, CodeAction>> generateToStringQuickAssist = getCodeActionFromProposal(params.getContext(), context.getCompilationUnit(), ActionMessages.GenerateToStringAction_label,
							JavaCodeActionKind.QUICK_ASSIST, generateToStringProposal);
					addSourceActionCommand($, params.getContext(), generateToStringQuickAssist);
				}
				// Generate Source Action
				Optional<Either<Command, CodeAction>> generateToStringCommand = getCodeActionFromProposal(params.getContext(), context.getCompilationUnit(), ActionMessages.GenerateToStringAction_label,
						JavaCodeActionKind.SOURCE_GENERATE_TO_STRING, generateToStringProposal);
				addSourceActionCommand($, params.getContext(), generateToStringCommand);
			}
		}

		// Generate Constructors
		if (generateConstructors == null) {
			generateConstructors = getGenerateConstructorsAction(params, context, type, JavaCodeActionKind.SOURCE_GENERATE_CONSTRUCTORS, monitor);
		} else if (generateConstructors.isPresent()) {
			Command command = new Command(ActionMessages.GenerateConstructorsAction_ellipsisLabel, COMMAND_ID_ACTION_GENERATECONSTRUCTORSPROMPT, Collections.singletonList(params));
			if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(JavaCodeActionKind.SOURCE_GENERATE_CONSTRUCTORS)) {
				CodeAction codeAction = new CodeAction(ActionMessages.GenerateConstructorsAction_ellipsisLabel);
				codeAction.setKind(JavaCodeActionKind.SOURCE_GENERATE_CONSTRUCTORS);
				codeAction.setCommand(command);
				codeAction.setDiagnostics(Collections.emptyList());
				generateConstructors = Optional.of(Either.forRight(codeAction));
			} else {
				generateConstructors = Optional.of(Either.forLeft(command));
			}
		}
		addSourceActionCommand($, params.getContext(), generateConstructors);

		// Generate Delegate Methods
		Optional<Either<Command, CodeAction>> generateDelegateMethods = getGenerateDelegateMethodsAction(params, context, type);
		addSourceActionCommand($, params.getContext(), generateDelegateMethods);

		// Add final modifiers where possible
		Optional<Either<Command, CodeAction>> generateFinalModifiers = addFinalModifierWherePossibleAction(context);
		addSourceActionCommand($, params.getContext(), generateFinalModifiers);

		return $;
	}

	private void addSourceActionCommand(List<Either<Command, CodeAction>> result, CodeActionContext context, Optional<Either<Command, CodeAction>> target) {
		if (!target.isPresent()) {
			return;
		}

		Either<Command, CodeAction> targetAction = target.get();
		if (context.getOnly() != null && !context.getOnly().isEmpty()) {
			Stream<String> acceptedActionKinds = context.getOnly().stream();
			String actionKind = targetAction.getLeft() == null ? targetAction.getRight().getKind() : targetAction.getLeft().getCommand();
			if (!acceptedActionKinds.filter(kind -> actionKind != null && actionKind.startsWith(kind)).findFirst().isPresent()) {
				return;
			}
		}

		result.add(targetAction);
	}

	private TextEdit getOrganizeImportsProposal(IInvocationContext context, IProgressMonitor monitor) {
		ICompilationUnit unit = context.getCompilationUnit();
		CompilationUnit astRoot = context.getASTRoot();
		OrganizeImportsOperation op = new OrganizeImportsOperation(unit, astRoot, true, false, true, null);
		try {
			TextEdit edit = op.createTextEdit(monitor);
			TextEdit staticEdit = OrganizeImportsHandler.wrapStaticImports(edit, astRoot, unit);
			if (staticEdit.getChildrenSize() > 0) {
				return staticEdit;
			}
			return edit;
		} catch (OperationCanceledException | CoreException e) {
			JavaLanguageServerPlugin.logException("Resolve organize imports source action", e);
		}

		return null;
	}

	private Optional<Either<Command, CodeAction>> getOrganizeImportsAction(CodeActionParams params) {
		Command command = new Command(CorrectionMessages.ReorgCorrectionsSubProcessor_organizeimports_description, COMMAND_ID_ACTION_ORGANIZEIMPORTS, Collections.singletonList(params));
		CodeAction codeAction = new CodeAction(CorrectionMessages.ReorgCorrectionsSubProcessor_organizeimports_description);
		codeAction.setKind(CodeActionKind.SourceOrganizeImports);
		codeAction.setCommand(command);
		codeAction.setDiagnostics(Collections.EMPTY_LIST);
		return Optional.of(Either.forRight(codeAction));

	}

	private Optional<Either<Command, CodeAction>> getOverrideMethodsAction(CodeActionParams params) {
		if (!preferenceManager.getClientPreferences().isOverrideMethodsPromptSupported()) {
			return Optional.empty();
		}

		Command command = new Command(ActionMessages.OverrideMethodsAction_label, COMMAND_ID_ACTION_OVERRIDEMETHODSPROMPT, Collections.singletonList(params));
		if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(JavaCodeActionKind.SOURCE_OVERRIDE_METHODS)) {
			CodeAction codeAction = new CodeAction(ActionMessages.OverrideMethodsAction_label);
			codeAction.setKind(JavaCodeActionKind.SOURCE_OVERRIDE_METHODS);
			codeAction.setCommand(command);
			codeAction.setDiagnostics(Collections.EMPTY_LIST);
			return Optional.of(Either.forRight(codeAction));
		} else {
			return Optional.of(Either.forLeft(command));
		}
	}

	private Optional<Either<Command, CodeAction>> getGetterSetterAction(CodeActionParams params, IInvocationContext context, IType type, String kind, boolean isInTypeDeclaration) {
		try {
			AccessorField[] accessors = GenerateGetterSetterOperation.getUnimplementedAccessors(type);
			if (accessors == null || accessors.length == 0) {
				return Optional.empty();
			} else if (accessors.length == 1 || !preferenceManager.getClientPreferences().isAdvancedGenerateAccessorsSupported()) {
				CodeActionProposal getAccessorsProposal = (pm) -> {
					// If cursor position is not specified, then insert to the last by default.
					IJavaElement insertBefore = isInTypeDeclaration ? CodeGenerationUtils.findInsertElement(type, null) : CodeGenerationUtils.findInsertElement(type, params.getRange());
					GenerateGetterSetterOperation operation = new GenerateGetterSetterOperation(type, context.getASTRoot(), preferenceManager.getPreferences().isCodeGenerationTemplateGenerateComments(), insertBefore);
					TextEdit edit = operation.createTextEdit(pm, accessors);
					return convertToWorkspaceEdit(context.getCompilationUnit(), edit);
				};
				return getCodeActionFromProposal(params.getContext(), context.getCompilationUnit(), ActionMessages.GenerateGetterSetterAction_label, kind, getAccessorsProposal);
			} else {
				Command command = new Command(ActionMessages.GenerateGetterSetterAction_ellipsisLabel, COMMAND_ID_ACTION_GENERATEACCESSORSPROMPT, Collections.singletonList(params));
				if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(JavaCodeActionKind.SOURCE_GENERATE_ACCESSORS)) {
					CodeAction codeAction = new CodeAction(ActionMessages.GenerateGetterSetterAction_ellipsisLabel);
					codeAction.setKind(kind);
					codeAction.setCommand(command);
					codeAction.setDiagnostics(Collections.emptyList());
					return Optional.of(Either.forRight(codeAction));
				} else {
					return Optional.of(Either.forLeft(command));
				}
			}
		} catch (OperationCanceledException | CoreException e) {
			JavaLanguageServerPlugin.logException("Failed to generate Getter and Setter source action", e);
			return Optional.empty();
		}
	}

	private boolean supportsHashCodeEquals(IInvocationContext context, IType type, IProgressMonitor monitor) {
		try {
			if (type == null || type.isAnnotation() || type.isInterface() || type.isEnum() || type.getCompilationUnit() == null) {
				return false;
			}

			CompilationUnit astRoot = context.getASTRoot();
			if (astRoot == null) {
				return false;
			}

			ITypeBinding typeBinding = ASTNodes.getTypeBinding(astRoot, type);
			return (typeBinding == null) ? false : Arrays.stream(typeBinding.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())).findAny().isPresent();
		} catch (JavaModelException e) {
			return false;
		}
	}

	private Optional<Either<Command, CodeAction>> getHashCodeEqualsAction(CodeActionParams params, String kind) {
		if (!preferenceManager.getClientPreferences().isHashCodeEqualsPromptSupported()) {
			return Optional.empty();
		}
		Command command = new Command(ActionMessages.GenerateHashCodeEqualsAction_label, COMMAND_ID_ACTION_HASHCODEEQUALSPROMPT, Collections.singletonList(params));
		if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(JavaCodeActionKind.SOURCE_GENERATE_HASHCODE_EQUALS)) {
			CodeAction codeAction = new CodeAction(ActionMessages.GenerateHashCodeEqualsAction_label);
			codeAction.setKind(kind);
			codeAction.setCommand(command);
			codeAction.setDiagnostics(Collections.emptyList());
			return Optional.of(Either.forRight(codeAction));
		} else {
			return Optional.of(Either.forLeft(command));
		}
	}

	private boolean supportsGenerateToString(IType type) {
		try {
			if (type == null || type.isAnnotation() || type.isInterface() || type.isEnum() || type.isAnonymous() || type.getCompilationUnit() == null) {
				return false;
			}
		} catch (JavaModelException e) {
			// do nothing.
		}

		return true;
	}

	private Optional<Either<Command, CodeAction>> getGenerateToStringAction(CodeActionParams params, String kind) {
		if (!preferenceManager.getClientPreferences().isGenerateToStringPromptSupported()) {
			return Optional.empty();
		}
		Command command = new Command(ActionMessages.GenerateToStringAction_ellipsisLabel, COMMAND_ID_ACTION_GENERATETOSTRINGPROMPT, Collections.singletonList(params));
		if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(JavaCodeActionKind.SOURCE_GENERATE_TO_STRING)) {
			CodeAction codeAction = new CodeAction(ActionMessages.GenerateToStringAction_ellipsisLabel);
			codeAction.setKind(kind);
			codeAction.setCommand(command);
			codeAction.setDiagnostics(Collections.emptyList());
			return Optional.of(Either.forRight(codeAction));
		} else {
			return Optional.of(Either.forLeft(command));
		}
	}

	private Optional<Either<Command, CodeAction>> getGenerateConstructorsAction(CodeActionParams params, IInvocationContext context, IType type, String kind, IProgressMonitor monitor) {
		try {
			if (type == null || type.isAnnotation() || type.isInterface() || type.isAnonymous() || type.getCompilationUnit() == null) {
				return Optional.empty();
			}
		} catch (JavaModelException e) {
			return Optional.empty();
		}

		if (preferenceManager.getClientPreferences().isGenerateConstructorsPromptSupported()) {
			CheckConstructorsResponse status = GenerateConstructorsHandler.checkConstructorStatus(type, monitor);
			if (status.constructors.length == 0) {
				return Optional.empty();
			}
			if (status.constructors.length == 1 && status.fields.length == 0) {
				CodeActionProposal generateConstructorsProposal = (pm) -> {
					TextEdit edit = GenerateConstructorsHandler.generateConstructors(type, status.constructors, status.fields, params.getRange(), pm);
					return convertToWorkspaceEdit(type.getCompilationUnit(), edit);
				};
				return getCodeActionFromProposal(params.getContext(), type.getCompilationUnit(), ActionMessages.GenerateConstructorsAction_label, kind, generateConstructorsProposal);
			}

			Command command = new Command(ActionMessages.GenerateConstructorsAction_ellipsisLabel, COMMAND_ID_ACTION_GENERATECONSTRUCTORSPROMPT, Collections.singletonList(params));
			if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(JavaCodeActionKind.SOURCE_GENERATE_CONSTRUCTORS)) {
				CodeAction codeAction = new CodeAction(ActionMessages.GenerateConstructorsAction_ellipsisLabel);
				codeAction.setKind(kind);
				codeAction.setCommand(command);
				codeAction.setDiagnostics(Collections.emptyList());
				return Optional.of(Either.forRight(codeAction));
			} else {
				return Optional.of(Either.forLeft(command));
			}
		}

		return Optional.empty();
	}

	private Optional<Either<Command, CodeAction>> getGenerateDelegateMethodsAction(CodeActionParams params, IInvocationContext context, IType type) {
		try {
			if (!preferenceManager.getClientPreferences().isGenerateDelegateMethodsPromptSupported() || !GenerateDelegateMethodsHandler.supportsGenerateDelegateMethods(type)) {
				return Optional.empty();
			}
		} catch (JavaModelException e) {
			return Optional.empty();
		}

		Command command = new Command(ActionMessages.GenerateDelegateMethodsAction_label, COMMAND_ID_ACTION_GENERATEDELEGATEMETHODSPROMPT, Collections.singletonList(params));
		if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(JavaCodeActionKind.SOURCE_GENERATE_DELEGATE_METHODS)) {
			CodeAction codeAction = new CodeAction(ActionMessages.GenerateDelegateMethodsAction_label);
			codeAction.setKind(JavaCodeActionKind.SOURCE_GENERATE_DELEGATE_METHODS);
			codeAction.setCommand(command);
			codeAction.setDiagnostics(Collections.EMPTY_LIST);
			return Optional.of(Either.forRight(codeAction));
		} else {
			return Optional.of(Either.forLeft(command));
		}
	}

	private Optional<Either<Command, CodeAction>> addFinalModifierWherePossibleAction(IInvocationContext context) {
		IProposableFix fix = (IProposableFix) VariableDeclarationFixCore.createCleanUp(context.getASTRoot(), true, true, true);

		if (fix == null) {
			return Optional.empty();
		}

		FixCorrectionProposal proposal = new FixCorrectionProposal(fix, null, IProposalRelevance.MAKE_VARIABLE_DECLARATION_FINAL, context, JavaCodeActionKind.SOURCE_GENERATE_FINAL_MODIFIERS);
		if (this.preferenceManager.getClientPreferences().isResolveCodeActionSupported()) {
			CodeAction codeAction = new CodeAction(ActionMessages.GenerateFinalModifiersAction_label);
			codeAction.setKind(proposal.getKind());
			codeAction.setData(proposal);
			codeAction.setDiagnostics(Collections.EMPTY_LIST);
			return Optional.of(Either.forRight(codeAction));
		} else {
			WorkspaceEdit edit;
			try {
				edit = ChangeUtil.convertToWorkspaceEdit(proposal.getChange());
			} catch (CoreException e) {
				JavaLanguageServerPlugin.logException("Problem converting proposal to code actions", e);
				return Optional.empty();
			}

			if (!ChangeUtil.hasChanges(edit)) {
				return Optional.empty();
			}
			Command command = new Command(ActionMessages.GenerateFinalModifiersAction_label, CodeActionHandler.COMMAND_ID_APPLY_EDIT, Collections.singletonList(edit));
			if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(proposal.getKind())) {
				CodeAction codeAction = new CodeAction(ActionMessages.GenerateFinalModifiersAction_label);
				codeAction.setKind(proposal.getKind());
				codeAction.setCommand(command);
				codeAction.setDiagnostics(Collections.EMPTY_LIST);
				return Optional.of(Either.forRight(codeAction));
			} else {
				return Optional.of(Either.forLeft(command));
			}
		}
	}

	private Optional<Either<Command, CodeAction>> getCodeActionFromProposal(CodeActionContext context, ICompilationUnit cu, String name, String kind, CodeActionProposal proposal) {
		if (preferenceManager.getClientPreferences().isResolveCodeActionSupported()) {
			CodeAction codeAction = new CodeAction(name);
			codeAction.setKind(kind);
			codeAction.setData(proposal);
			codeAction.setDiagnostics(Collections.EMPTY_LIST);
			return Optional.of(Either.forRight(codeAction));
		}

		try {
			WorkspaceEdit edit =  proposal.resolveEdit(new NullProgressMonitor());
			if (!ChangeUtil.hasChanges(edit)) {
				return Optional.empty();
			}

			Command command = new Command(name, CodeActionHandler.COMMAND_ID_APPLY_EDIT, Collections.singletonList(edit));
			if (preferenceManager.getClientPreferences().isSupportedCodeActionKind(kind)) {
				CodeAction codeAction = new CodeAction(name);
				codeAction.setKind(kind);
				codeAction.setCommand(command);
				codeAction.setDiagnostics(context.getDiagnostics());
				return Optional.of(Either.forRight(codeAction));
			} else {
				return Optional.of(Either.forLeft(command));
			}
		} catch (OperationCanceledException | CoreException e) {
			JavaLanguageServerPlugin.logException("Problem converting proposal to code actions", e);
		}

		return null;
	}

	private boolean hasFields(IType type, boolean includeStatic) throws JavaModelException {
		for (IField field : type.getFields()) {
			if (includeStatic || !JdtFlags.isStatic(field)) {
				return true;
			}
		}

		return false;
	}

	public static WorkspaceEdit convertToWorkspaceEdit(ICompilationUnit cu, TextEdit edit) {
		if (cu == null || edit == null) {
			return null;
		}

		WorkspaceEdit workspaceEdit = new WorkspaceEdit();
		TextEditConverter converter = new TextEditConverter(cu, edit);
		String uri = JDTUtils.toURI(cu);
		workspaceEdit.getChanges().put(uri, converter.convert());
		return workspaceEdit;
	}

	public static IType getSelectionType(IInvocationContext context) {
		ICompilationUnit unit = context.getCompilationUnit();
		ASTNode node = context.getCoveredNode();
		if (node == null) {
			node = context.getCoveringNode();
		}

		ITypeBinding typeBinding = null;
		while (node != null && !(node instanceof CompilationUnit)) {
			if (node instanceof AbstractTypeDeclaration) {
				typeBinding = ((AbstractTypeDeclaration) node).resolveBinding();
				break;
			} else if (node instanceof AnonymousClassDeclaration) { // Anonymous
				typeBinding = ((AnonymousClassDeclaration) node).resolveBinding();
				break;
			}

			node = node.getParent();
		}

		if (typeBinding != null && typeBinding.getJavaElement() instanceof IType) {
			return (IType) typeBinding.getJavaElement();
		}

		return unit.findPrimaryType();
	}

	public static IType getSelectionType(CodeActionParams params) {
		return getSelectionType(params, new NullProgressMonitor());
	}

	public static IType getSelectionType(CodeActionParams params, IProgressMonitor monitor) {
		InnovationContext context = getInnovationContext(params, monitor);
		return (context == null) ? null : getSelectionType(context);
	}

	public static InnovationContext getInnovationContext(CodeActionParams params, IProgressMonitor monitor) {
		final ICompilationUnit unit = JDTUtils.resolveCompilationUnit(params.getTextDocument().getUri());
		if (unit == null) {
			return null;
		}
		int start = DiagnosticsHelper.getStartOffset(unit, params.getRange());
		int end = DiagnosticsHelper.getEndOffset(unit, params.getRange());
		InnovationContext context = new InnovationContext(unit, start, end - start);
		CompilationUnit astRoot = CoreASTProvider.getInstance().getAST(unit, CoreASTProvider.WAIT_YES, monitor);
		if (astRoot == null) {
			return null;
		}

		context.setASTRoot(astRoot);
		return context;
	}

	public static ICompilationUnit getCompilationUnit(CodeActionParams params) {
		return JDTUtils.resolveCompilationUnit(params.getTextDocument().getUri());
	}

	public static ASTNode getDeclarationNode(ASTNode node) {
		if (node == null) {
			return null;
		}
		if (node instanceof BodyDeclaration) {
			return null;
		}
		while (node != null && !(node instanceof BodyDeclaration) && !(node instanceof Statement)) {
			node = node.getParent();
		}
		return node;
	}

	private static boolean isInTypeDeclaration(IInvocationContext context) {
		ASTNode node = context.getCoveredNode();
		if (node == null) {
			node = context.getCoveringNode();
		}
		ASTNode declarationNode = getDeclarationNode(node);
		return declarationNode instanceof TypeDeclaration;
	}
}
