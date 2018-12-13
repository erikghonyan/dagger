/*
 * Copyright (C) 2018 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.DaggerStreams.toImmutableSetMultimap;
import static dagger.internal.codegen.Formatter.INDENT;
import static dagger.internal.codegen.Optionals.emptiesLast;
import static dagger.model.BindingKind.MEMBERS_INJECTION;
import static java.util.Comparator.comparing;
import static javax.tools.Diagnostic.Kind.ERROR;

import com.google.auto.value.AutoValue;
import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import dagger.model.Binding;
import dagger.model.BindingGraph;
import dagger.model.BindingGraph.Node;
import dagger.model.ComponentPath;
import dagger.model.DependencyRequest;
import dagger.model.Key;
import dagger.spi.BindingGraphPlugin;
import dagger.spi.DiagnosticReporter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** Reports errors for conflicting bindings with the same key. */
final class DuplicateBindingsValidator implements BindingGraphPlugin {

  // 1. contributing module or enclosing type
  // 2. binding element's simple name
  // 3. binding element's type
  private static final Comparator<BindingDeclaration> BINDING_DECLARATION_COMPARATOR =
      comparing(
              (BindingDeclaration declaration) ->
                  declaration.contributingModule().isPresent()
                      ? declaration.contributingModule()
                      : declaration.bindingTypeElement(),
              emptiesLast(comparing((TypeElement type) -> type.getQualifiedName().toString())))
          .thenComparing(
              (BindingDeclaration declaration) -> declaration.bindingElement(),
              emptiesLast(
                  comparing((Element element) -> element.getSimpleName().toString())
                      .thenComparing((Element element) -> element.asType().toString())));

  /**
   * An {@link Equivalence} between {@link Binding}s that ignores the {@link
   * Binding#componentPath()}. (All other properties are essentially derived from the {@link
   * Binding#bindingElement()} and {@link Binding#contributingModule()}, so only those two are
   * compared.)
   */
  // TODO(dpb): Move to dagger.model?
  private static final Equivalence<Binding> IGNORING_COMPONENT_PATH =
      new Equivalence<Binding>() {
        @Override
        protected boolean doEquivalent(Binding a, Binding b) {
          return a.bindingElement().equals(b.bindingElement())
              && a.contributingModule().equals(b.contributingModule());
        }

        @Override
        protected int doHash(Binding binding) {
          return Objects.hash(binding.bindingElement(), binding.contributingModule());
        }
      };

  private final BindingDeclarationFormatter bindingDeclarationFormatter;

  @Inject
  DuplicateBindingsValidator(BindingDeclarationFormatter bindingDeclarationFormatter) {
    this.bindingDeclarationFormatter = bindingDeclarationFormatter;
  }

  @Override
  public String pluginName() {
    return "Dagger/DuplicateBindings";
  }

  @Override
  public void visitGraph(BindingGraph bindingGraph, DiagnosticReporter diagnosticReporter) {
    // If two unrelated subcomponents have the same duplicate bindings only because they install the
    // same two modules, then fixing the error in one subcomponent will uncover the second
    // subcomponent to fix.
    // TODO(ronshapiro): Explore ways to address such underreporting without overreporting.
    Set<ImmutableSet<Equivalence.Wrapper<Binding>>> reportedDuplicateBindingSets = new HashSet<>();
    duplicateBindingSets(bindingGraph)
        .forEach(
            duplicateBindings -> {
              // Only report each set of duplicate bindings once, ignoring the installed component.
              if (reportedDuplicateBindingSets.add(duplicateBindings)) {
                reportDuplicateBindings(
                    duplicateBindings.stream()
                        .map(Equivalence.Wrapper::get)
                        .collect(toImmutableSet()),
                    bindingGraph,
                    diagnosticReporter);
              }
            });
  }

  /**
   * Returns sets of duplicate bindings. Bindings are duplicates if they bind the same key and are
   * visible from the same component. Two bindings that differ only in the component that owns them
   * are not considered to be duplicates, because that means the same binding was "copied" down to a
   * descendant component because it depends on local multibindings or optional bindings.
   */
  private ImmutableSet<ImmutableSet<Equivalence.Wrapper<Binding>>> duplicateBindingSets(
      BindingGraph bindingGraph) {
    return groupBindingsByKey(bindingGraph).stream()
        .flatMap(bindings -> mutuallyVisibleSubsets(bindings).stream())
        .map(DuplicateBindingsValidator::equivalentSetIgnoringComponentPath)
        .filter(duplicates -> duplicates.size() > 1)
        .collect(toImmutableSet());
  }

  private static ImmutableSet<ImmutableSet<Binding>> groupBindingsByKey(BindingGraph bindingGraph) {
    return valueSetsForEachKey(
        bindingGraph.bindings().stream()
            .filter(binding -> !binding.kind().equals(MEMBERS_INJECTION))
            .collect(toImmutableSetMultimap(Binding::key, binding -> binding)));
  }

  /**
   * Returns the subsets of the input set that contain bindings that are all visible from the same
   * component. A binding is visible from its component and all its descendants.
   */
  private static ImmutableSet<ImmutableSet<Binding>> mutuallyVisibleSubsets(
      Set<Binding> duplicateBindings) {
    ImmutableListMultimap<ComponentPath, Binding> bindingsByComponentPath =
        Multimaps.index(duplicateBindings, Binding::componentPath);
    ImmutableSetMultimap.Builder<ComponentPath, Binding> mutuallyVisibleBindings =
        ImmutableSetMultimap.builder();
    bindingsByComponentPath
        .asMap()
        .forEach(
            (componentPath, bindings) -> {
              mutuallyVisibleBindings.putAll(componentPath, bindings);
              for (ComponentPath ancestor = componentPath; !ancestor.atRoot(); ) {
                ancestor = ancestor.parent();
                ImmutableList<Binding> bindingsInAncestor = bindingsByComponentPath.get(ancestor);
                mutuallyVisibleBindings.putAll(componentPath, bindingsInAncestor);
              }
            });
    return valueSetsForEachKey(mutuallyVisibleBindings.build());
  }

  private static <E> ImmutableSet<ImmutableSet<E>> valueSetsForEachKey(Multimap<?, E> multimap) {
    return multimap.asMap().values().stream().map(ImmutableSet::copyOf).collect(toImmutableSet());
  }

  private void reportDuplicateBindings(
      ImmutableSet<Binding> duplicateBindings,
      BindingGraph bindingGraph,
      DiagnosticReporter diagnosticReporter) {
    Binding oneBinding = duplicateBindings.asList().get(0);
    diagnosticReporter.reportBinding(
        ERROR,
        oneBinding,
        Iterables.any(duplicateBindings, binding -> binding.kind().isMultibinding())
            ? incompatibleBindingsMessage(oneBinding.key(), duplicateBindings, bindingGraph)
            : duplicateBindingMessage(oneBinding.key(), duplicateBindings, bindingGraph));
  }

  private String duplicateBindingMessage(
      Key key, ImmutableSet<Binding> duplicateBindings, BindingGraph graph) {
    StringBuilder message = new StringBuilder().append(key).append(" is bound multiple times:");
    formatDeclarations(message, 1, declarations(graph, duplicateBindings));
    return message.toString();
  }

  private String incompatibleBindingsMessage(
      Key key, ImmutableSet<Binding> duplicateBindings, BindingGraph graph) {
    ImmutableSet<dagger.model.Binding> multibindings =
        duplicateBindings.stream()
            .filter(binding -> binding.kind().isMultibinding())
            .collect(toImmutableSet());
    verify(
        multibindings.size() == 1, "expected only one multibinding for %s: %s", key, multibindings);
    StringBuilder message = new StringBuilder();
    java.util.Formatter messageFormatter = new java.util.Formatter(message);
    messageFormatter.format("%s has incompatible bindings or declarations:\n", key);
    message.append(INDENT);
    dagger.model.Binding multibinding = getOnlyElement(multibindings);
    messageFormatter.format("%s bindings and declarations:", multibindingTypeString(multibinding));
    formatDeclarations(message, 2, declarations(graph, multibindings));

    Set<dagger.model.Binding> uniqueBindings =
        Sets.filter(duplicateBindings, binding -> !binding.equals(multibinding));
    message.append('\n').append(INDENT).append("Unique bindings and declarations:");
    formatDeclarations(
        message,
        2,
        Sets.filter(
            declarations(graph, uniqueBindings),
            declaration -> !(declaration instanceof MultibindingDeclaration)));
    return message.toString();
  }

  private void formatDeclarations(
      StringBuilder builder,
      int indentLevel,
      Iterable<? extends BindingDeclaration> bindingDeclarations) {
    bindingDeclarationFormatter.formatIndentedList(
        builder, ImmutableList.copyOf(bindingDeclarations), indentLevel);
  }

  private ImmutableSet<BindingDeclaration> declarations(
      BindingGraph graph, Set<dagger.model.Binding> bindings) {
    return bindings.stream()
        .flatMap(binding -> declarations(graph, binding).stream())
        .distinct()
        .sorted(BINDING_DECLARATION_COMPARATOR)
        .collect(toImmutableSet());
  }

  private ImmutableSet<BindingDeclaration> declarations(
      BindingGraph graph, dagger.model.Binding binding) {
    ImmutableSet.Builder<BindingDeclaration> declarations = ImmutableSet.builder();
    BindingNode bindingNode = (BindingNode) binding;
    bindingNode.associatedDeclarations().forEach(declarations::add);
    if (bindingDeclarationFormatter.canFormat(bindingNode.delegate())) {
      declarations.add(bindingNode.delegate());
    } else {
      graph.requestedBindings(binding).stream()
          .flatMap(requestedBinding -> declarations(graph, requestedBinding).stream())
          .forEach(declarations::add);
    }
    return declarations.build();
  }

  private String multibindingTypeString(dagger.model.Binding multibinding) {
    switch (multibinding.kind()) {
      case MULTIBOUND_MAP:
        return "Map";
      case MULTIBOUND_SET:
        return "Set";
      default:
        throw new AssertionError(multibinding);
    }
  }

  private static ImmutableSet<Equivalence.Wrapper<Binding>> equivalentSetIgnoringComponentPath(
      ImmutableSet<Binding> resolvedBindings) {
    return resolvedBindings.stream().map(IGNORING_COMPONENT_PATH::wrap).collect(toImmutableSet());
  }

  @AutoValue
  abstract static class SourceAndRequest {

    abstract Node source();

    abstract DependencyRequest request();

    static SourceAndRequest create(Node source, DependencyRequest request) {
      return new AutoValue_DuplicateBindingsValidator_SourceAndRequest(source, request);
    }
  }
}
