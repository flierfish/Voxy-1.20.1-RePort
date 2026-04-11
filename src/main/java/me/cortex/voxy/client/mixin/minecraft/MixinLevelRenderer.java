package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxyRenderSystem renderer;

    @Unique
    private static final long COOLDOWN_MS = 5000L;
    @Unique
    private volatile long voxy$lastRebuildTime;
    @Unique
    private volatile boolean voxy$pendingRebuild;

    @Override
    public VoxyRenderSystem getVoxyRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)//We want to inject before sodium
    private void reloadVoxyRenderer(CallbackInfo ci) {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRebuild = currentTime - this.voxy$lastRebuildTime;
    
        if (timeSinceLastRebuild >= COOLDOWN_MS) {
            // Enough time has passed, execute immediately
            this.voxy$lastRebuildTime = currentTime;
            this.voxy$pendingRebuild = false;
            this.executeRebuild();
        } else {
            // Within cooldown, schedule deferred execution using a simple daemon thread
            if (!this.voxy$pendingRebuild) {
                this.voxy$pendingRebuild = true;
                long delay = COOLDOWN_MS - timeSinceLastRebuild;
                Thread delayThread = new Thread(() -> {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (this.voxy$pendingRebuild) {
                        this.voxy$pendingRebuild = false;
                        Minecraft.getInstance().execute(() -> {
                            this.voxy$lastRebuildTime = System.currentTimeMillis();
                            this.executeRebuild();
                        });
                    }
                });
                delayThread.setDaemon(true);
                delayThread.setName("Voxy-Debounce");
                delayThread.start();
            }
        }
    }

    @Unique
    private void executeRebuild() {
        this.shutdownRenderer();
        if (this.level != null) {
            this.createRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        if (this.level != world) {
            this.shutdownRenderer();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void injectClose(CallbackInfo ci) {
        this.shutdownRenderer();
    }

    @Override
    public void shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.level);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        if (IrisUtil.SHADER_SUPPORT && IrisUtil.irisShaderPackEnabled()) {
            IrisUtil.voxypipelinepatch();
        }
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                IrisUtil.disableIrisShaders();
            } else {
                throw e;
            }
        }
        instance.updateDedicatedThreads();
    }
}
