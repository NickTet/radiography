package radiography.internal

import android.view.View
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionReference
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntBounds
import androidx.ui.tooling.Group
import androidx.ui.tooling.NodeGroup
import androidx.ui.tooling.asTree
import kotlin.LazyThreadSafetyMode.PUBLICATION

/**
 * Information about a Compose `LayoutNode`, extracted from a [Group] tree via [Group.layoutInfos].
 *
 * This is a useful layer of indirection from directly handling Groups because it allows us to
 * define our own notion of what an atomic unit of "composable" is independently from how Compose
 * actually represents things under the hood. When this changes in some future dev version, we
 * only need to update the "parsing" logic in this file.
 * It's also helpful since we actually gather data from multiple Groups for a single LayoutInfo,
 * so parsing them ahead of time into these objects means the visitor can be stateless.
 */
internal class ComposeLayoutInfo(
  val name: String,
  val bounds: IntBounds,
  val modifiers: List<Modifier>,
  val children: Sequence<ComposeLayoutInfo>,
  val view: View?
)

/**
 * A sequence that lazily parses [ComposeLayoutInfo]s from a [Group] tree.
 */
internal val Group.layoutInfos: Sequence<ComposeLayoutInfo> get() = computeLayoutInfos()

/**
 * Recursively parses [ComposeLayoutInfo]s from a [Group]. Groups form a tree and can contain different
 * type of nodes which represent function calls, arbitrary data stored directly in the slot table,
 * or just subtrees.
 *
 * This function walks the tree and collects only Groups which represent emitted values
 * ([NodeGroup]s). These either represent `LayoutNode`s (Compose's internal primitive for layout
 * algorithms) or classic Android views that the composition emitted. This function collapses all
 * the groups in between each of these nodes, but uses the top-most Group under the previous node
 * to derive the "name" of the [ComposeLayoutInfo]. The other [ComposeLayoutInfo] properties come directly off
 * [NodeGroup] values.
 */
private fun Group.computeLayoutInfos(
  parentName: String = ""
): Sequence<ComposeLayoutInfo> {
  val name = parentName.ifBlank { this.name }.orEmpty()

  val subComposedChildren = getCompositionReferences()
      .flatMap { it.tryGetComposers().asSequence() }
      .map { subcomposer ->
        ComposeLayoutInfo(
            name = "$name (subcomposition)",
            bounds = box,
            modifiers = emptyList(),
            children = subcomposer.slotTable.asTree().layoutInfos,
            view = null
        )
      }

  if (this !is NodeGroup) {
    return subComposedChildren + children.asSequence()
        .flatMap { it.computeLayoutInfos(name) }
        .constrainOnce()
  }

  val children = subComposedChildren + children.asSequence()
      // This node will "consume" the name, so reset it name to empty for children.
      .flatMap { it.computeLayoutInfos() }
      .constrainOnce()

  val layoutInfo = ComposeLayoutInfo(
      name = name,
      bounds = box,
      modifiers = modifierInfo.map { it.modifier },
      children = children,
      view = node as? View
  )
  return sequenceOf(layoutInfo)
}

private val COMPOSITION_REFERENCE_HOLDER_CLASS by lazy(PUBLICATION) {
  try {
    Class.forName("androidx.compose.runtime.Composer\$CompositionReferenceHolder")
  } catch (e: Throwable) {
    null
  }
}
private val COMPOSITION_REFERENCE_IMPL_CLASS by lazy(PUBLICATION) {
  try {
    Class.forName("androidx.compose.runtime.Composer\$CompositionReferenceImpl")
  } catch (e: Throwable) {
    null
  }
}
private val COMPOSITION_REFERENCE_HOLDER_REF_FIELD by lazy(PUBLICATION) {
  try {
    COMPOSITION_REFERENCE_HOLDER_CLASS?.getDeclaredField("ref")
        ?.apply { isAccessible = true }
  } catch (e: Throwable) {
    null
  }
}
private val COMPOSITION_REFERENCE_IMPL_COMPOSERS_FIELD by lazy(PUBLICATION) {
  try {
    COMPOSITION_REFERENCE_IMPL_CLASS?.getDeclaredField("composers")
        ?.apply { isAccessible = true }
  } catch (e: Throwable) {
    null
  }
}

private fun Group.getCompositionReferences(): Sequence<CompositionReference> {
  if (COMPOSITION_REFERENCE_HOLDER_CLASS == null) return emptySequence()

  return data.asSequence()
      .filter { it != null && it::class.java == COMPOSITION_REFERENCE_HOLDER_CLASS }
      .mapNotNull { holder -> holder.tryGetCompositionReference() }
//      .flatMap { ref -> ref.tryGetComposers().asSequence() }
}

private fun Any?.tryGetCompositionReference() =
  COMPOSITION_REFERENCE_HOLDER_REF_FIELD?.get(this) as? CompositionReference

@Suppress("UNCHECKED_CAST")
private fun CompositionReference.tryGetComposers(): Iterable<Composer<*>> {
  if (COMPOSITION_REFERENCE_IMPL_CLASS?.isInstance(this) != true) return emptyList()
  return COMPOSITION_REFERENCE_IMPL_COMPOSERS_FIELD?.get(this) as? Iterable<Composer<*>>
      ?: emptyList()
}
