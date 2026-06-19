package com.videoplayer.app.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryBodyStateTest {
    @Test fun `no permission shows permission regardless of loading or items`() {
        assertThat(libraryBodyState(hasPermission = false, isLoading = true, hasAnyItems = false))
            .isEqualTo(LibraryBodyState.PERMISSION)
        assertThat(libraryBodyState(hasPermission = false, isLoading = false, hasAnyItems = true))
            .isEqualTo(LibraryBodyState.PERMISSION)
    }
    @Test fun `granted and loading shows loading`() {
        assertThat(libraryBodyState(hasPermission = true, isLoading = true, hasAnyItems = false))
            .isEqualTo(LibraryBodyState.LOADING)
    }
    @Test fun `granted, loaded, no items shows empty`() {
        assertThat(libraryBodyState(hasPermission = true, isLoading = false, hasAnyItems = false))
            .isEqualTo(LibraryBodyState.EMPTY)
    }
    @Test fun `granted, loaded, with items shows content`() {
        assertThat(libraryBodyState(hasPermission = true, isLoading = false, hasAnyItems = true))
            .isEqualTo(LibraryBodyState.CONTENT)
    }
}
