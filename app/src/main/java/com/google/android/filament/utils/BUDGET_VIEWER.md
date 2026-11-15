# BudgetModelViewer provenance

Based on Filament v1.75.0 ModelViewer.kt, Apache-2.0 (original header retained).
Source: https://github.com/google/filament/blob/v1.75.0/android/filament-utils-android/src/main/java/com/google/android/filament/utils/ModelViewer.kt
Unmodified source SHA256: 393932CEB73B908F572A57A00E0299811403D58CB31544930C29976635E170B5.

Local changes: rename the class to BudgetModelViewer; add createOwnedAssetLoader(),
loadModelGlbSynchronous(), loadOwnedResources(), cancelOwnedResources() and a mode
guard reset by destroyModel(). The progress getter reports completed synchronous
loading as1 instead of querying an async job that never started. Main uses synchronous reference loading before
loading the second asset, so one ResourceLoader owns both assets' texture
providers. The unused upstream async APIs remain present; the mode guard prevents
owned loading after an asynchronous reference. Native cancellation/drain and the
render/frame-pacing/texture callback paths remain unchanged.
The additional loader uses the viewer's existing material provider. The application
destroys the additional assets/loaders before the viewer's detach callback destroys
the shared provider/engine. A vehicle change recreates the whole viewer, preserving
the camera bookmark; this prevents retaining another vehicle's material cache.
The additional importer cancels/evicts the shared loader before asset destruction;
the viewer alone destroys the loader/providers, after both assets are destroyed.
The app compiles to JVM17, matching the pinned Filament utility inline functions;
Android minSdk28 and targetSdk34 remain unchanged.

MappedModelInput uses Android SharedMemory solely as process-private GLB staging.
It exports no descriptors and unmaps after import or failure/cancellation, independent
of GC. The Filament AssetLoader API example clears caller content immediately after
createAsset; this implementation conservatively retains it through loadResources.
No budget limits or source vehicle assets change.
