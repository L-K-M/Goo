package ch.lkmc.goo.di

import android.content.Context
import ch.lkmc.goo.data.ImageLoader
import ch.lkmc.goo.data.ImageSaver
import ch.lkmc.goo.data.OnboardingPrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader =
        ImageLoader(context)

    @Provides
    @Singleton
    fun provideImageSaver(@ApplicationContext context: Context): ImageSaver =
        ImageSaver(context)

    @Provides
    @Singleton
    fun provideOnboardingPrefs(@ApplicationContext context: Context): OnboardingPrefs =
        OnboardingPrefs(context)
}
