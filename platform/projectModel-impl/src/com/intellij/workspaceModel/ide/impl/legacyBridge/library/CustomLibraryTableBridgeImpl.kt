// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide.impl.legacyBridge.library

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.libraries.CustomLibraryTable
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablePresentation
import com.intellij.openapi.util.Disposer
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryId
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.serialization.impl.JpsLibraryEntitiesSerializer
import com.intellij.platform.workspace.storage.*
import com.intellij.platform.workspace.storage.impl.VersionedEntityStorageOnBuilder
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentationApi
import com.intellij.platform.workspace.storage.instrumentation.MutableEntityStorageInstrumentation
import com.intellij.workspaceModel.ide.impl.GlobalWorkspaceModel
import com.intellij.workspaceModel.ide.impl.legacyBridge.library.ProjectLibraryTableBridgeImpl.Companion.libraryMap
import com.intellij.workspaceModel.ide.impl.legacyBridge.library.ProjectLibraryTableBridgeImpl.Companion.mutableLibraryMap
import com.intellij.workspaceModel.ide.legacyBridge.CustomLibraryTableBridge
import org.jdom.Element
import org.jetbrains.jps.model.serialization.library.JpsLibraryTableSerializer
import org.jetbrains.jps.model.serialization.module.JpsModuleRootModelSerializer

internal class CustomLibraryTableBridgeImpl(private val level: String, private val presentation: LibraryTablePresentation)
  : CustomLibraryTableBridge, CustomLibraryTable, Disposable {
  private var tmpEntityStorage: EntityStorage? = null
  private val entitySource = LegacyCustomLibraryEntitySource(tableLevel)
  private val libraryTableId = LibraryTableId.GlobalLibraryTableId(tableLevel)
  private val libraryTableDelegate = GlobalLibraryTableDelegate(this, libraryTableId)

  override fun initializeBridgesAfterLoading(mutableStorage: MutableEntityStorage,
                                                    initialEntityStorage: VersionedEntityStorage): () -> Unit {
    return libraryTableDelegate.initializeLibraryBridgesAfterLoading(mutableStorage, initialEntityStorage)
  }

  override fun initializeBridges(changes: Map<Class<*>, List<EntityChange<*>>>, builder: MutableEntityStorage) {
    libraryTableDelegate.initializeLibraryBridges(changes, builder)
  }

  override fun handleBeforeChangeEvents(event: VersionedStorageChange) {
    libraryTableDelegate.handleBeforeChangeEvents(event)
  }

  override fun handleChangedEvents(event: VersionedStorageChange) {
    libraryTableDelegate.handleChangedEvents(event)
  }

  override fun getLibraries(): Array<Library> {
    return tmpEntityStorage?.entities(LibraryEntity::class.java)
             ?.filter { it.tableId == libraryTableId }
             ?.mapNotNull { tmpEntityStorage?.libraryMap?.getDataByEntity(it) }
             ?.toList()
             ?.toTypedArray()
           ?: libraryTableDelegate.getLibraries()
  }

  override fun getLibraryIterator(): Iterator<Library> = getLibraries().iterator()

  override fun getLibraryByName(name: String): Library? {
    return tmpEntityStorage?.resolve(LibraryId(name, libraryTableId))?.let { entity ->
      tmpEntityStorage?.libraryMap?.getDataByEntity(entity)
    } ?: libraryTableDelegate.getLibraryByName(name)
  }

  override fun createLibrary(): Library = createLibrary(null)

  override fun createLibrary(name: String?): Library = libraryTableDelegate.createLibrary(name)

  override fun removeLibrary(library: Library): Unit = libraryTableDelegate.removeLibrary(library)

  override fun getTableLevel(): String = level

  override fun getPresentation(): LibraryTablePresentation = presentation

  override fun getModifiableModel(): LibraryTable.ModifiableModel {
    return GlobalOrCustomModifiableLibraryTableBridgeImpl(this, entitySource)
  }

  override fun isEditable(): Boolean = false

  override fun dispose() {
    if (libraries.isEmpty()) return
    Disposer.dispose(libraryTableDelegate)

    val runnable: () -> Unit = {
      // We need to remove all related libraries from the [GlobalWorkspaceModel] e.g. extension point and related [CustomLibraryTable] can be unloaded
      GlobalWorkspaceModel.getInstance().updateModel("Cleanup custom libraries after dispose") { storage ->
        storage.entities(LibraryEntity::class.java).filter { it.entitySource == entitySource }.forEach {
          storage.removeEntity(it)
        }
      }
    }

    val application = ApplicationManager.getApplication()
    if (application.isWriteAccessAllowed) {
      runnable.invoke()
    }
    else {
      application.invokeLater {
        application.runWriteAction {
          runnable.invoke()
        }
      }
    }
  }

  @OptIn(EntityStorageInstrumentationApi::class)
  override fun readExternal(libraryTableTag: Element) {
    val globalWorkspaceModel = GlobalWorkspaceModel.getInstance()
    val mutableEntityStorage = MutableEntityStorage.create()

    libraryTableTag.getChildren(JpsLibraryTableSerializer.LIBRARY_TAG).forEach { libraryTag ->
      val name = libraryTag.getAttributeValue(JpsModuleRootModelSerializer.NAME_ATTRIBUTE)
      val libraryEntity = JpsLibraryEntitiesSerializer.loadLibrary(name, libraryTag, libraryTableId, entitySource,
                                                                   globalWorkspaceModel.getVirtualFileUrlManager())
      mutableEntityStorage.addEntity(libraryEntity)
    }

    if (!(mutableEntityStorage as MutableEntityStorageInstrumentation).hasChanges()) return

    // Based on the assumption that no one changes the application library file manually, we can reuse existing bridges
    val storageOnBuilder = VersionedEntityStorageOnBuilder(mutableEntityStorage)
    mutableEntityStorage.entities(LibraryEntity::class.java).forEach { libraryEntity ->
      val globalSnapshot = globalWorkspaceModel.currentSnapshot
      val originalLibrary = globalSnapshot.resolve(libraryEntity.symbolicId)
      val (actualLibraryEntity, entityStorage) = if (originalLibrary != null) {
        originalLibrary to globalSnapshot
      }
      else {
        libraryEntity to mutableEntityStorage
      }

      val libraryBridge = actualLibraryEntity.findLibraryBridge(entityStorage) ?: LibraryBridgeImpl(
        libraryTable = this,
        project = null,
        initialId = libraryEntity.symbolicId,
        initialEntityStorage = storageOnBuilder,
        targetBuilder = null
      )
      mutableEntityStorage.mutableLibraryMap.addMapping(libraryEntity, libraryBridge as LibraryBridge)
    }
    tmpEntityStorage = mutableEntityStorage

    val runnable: () -> Unit = {
      globalWorkspaceModel.updateModel("Custom library table ${libraryTableId.level} update") { builder ->
        builder.replaceBySource({ it == entitySource }, mutableEntityStorage)
      }
      tmpEntityStorage = null
    }

    val application = ApplicationManager.getApplication()
    if (application.isWriteAccessAllowed) {
      runnable.invoke()
    }
    else {
      application.invokeLater {
        application.runWriteAction {
          runnable.invoke()
        }
      }
    }
  }

  override fun writeExternal(element: Element) {
    GlobalWorkspaceModel.getInstance().currentSnapshot.entities(LibraryEntity::class.java)
      .filter { it.tableId == libraryTableId }
      .sortedBy { it.name }
      .forEach { libraryEntity ->
        val libraryTag = JpsLibraryEntitiesSerializer.saveLibrary(libraryEntity, null, false)
        element.addContent(libraryTag)
      }
  }

  override fun addListener(listener: LibraryTable.Listener) = libraryTableDelegate.addListener(listener)

  override fun addListener(listener: LibraryTable.Listener, parentDisposable: Disposable): Unit = libraryTableDelegate.addListener(listener, parentDisposable)

  override fun removeListener(listener: LibraryTable.Listener) = libraryTableDelegate.removeListener(listener)
}

data class LegacyCustomLibraryEntitySource(private val levelId: String): EntitySource