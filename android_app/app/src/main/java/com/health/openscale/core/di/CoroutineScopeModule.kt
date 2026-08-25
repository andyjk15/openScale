/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * THE ONE APPLICATION-LIFETIME [CoroutineScope] IN THIS APP. Before this existed,
 * [com.health.openscale.OpenScaleApp] built its own private one and
 * [com.health.openscale.core.usecase.TrackAppSyncUseCase] built a SECOND, entirely
 * unrelated one for its own fire-and-forget pushes — neither tied to anything's
 * lifecycle, which under Robolectric surfaced as a coroutine from one test's
 * [TrackAppSyncUseCase.pushAsync] throwing an "uncaught exception" attributed to a
 * LATER, unrelated test (BluetoothViewModelTest), because the launch had already
 * outlived the test that started it. A single injected scope doesn't fix every way
 * a fire-and-forget launch can outlive its caller, but it at least means there is
 * one scope to reason about instead of two silently identical ones.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
