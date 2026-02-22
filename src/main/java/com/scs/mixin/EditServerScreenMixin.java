package com.scs.mixin;

import com.scs.client.ServerMetadata;
import com.scs.core.SCS;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;

@Mixin(EditServerScreen.class)
public abstract class EditServerScreenMixin {

    private int[] labelYPositions = new int[2]; // 0 = Server Name Y, 1 = Server Address Y
    private EditBox customField;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        EditServerScreen screen = (EditServerScreen) (Object) this;

        int[] index = {0};
        screen.children().stream()
                .filter(c -> c instanceof EditBox)
                .map(c -> (EditBox) c)
                .forEach(editBox -> {
                    editBox.setY(editBox.getY() - 30);
                    if (index[0] < 2) {
                        labelYPositions[index[0]++] = editBox.getY() - 10;
                    }
                });

        screen.children().stream()
                .filter(c -> c instanceof CycleButton<?>)
                .map(c -> (CycleButton<?>) c)
                .filter(button -> button.getMessage().getString().contains("Resource"))
                .forEach(button -> button.setY(button.getY() + 18));

        customField = new EditBox(
                mc.font,
                mc.getWindow().getGuiScaledWidth() / 2 - 100,
                mc.getWindow().getGuiScaledHeight() / 4 + 60,
                200, 20,
                Component.translatable("screen.scs.download_url")
        );
        customField.setMaxLength(100);

        // Fill the custom field if metadata exists
        EditServerScreenAccessor accessor = (EditServerScreenAccessor) screen;
        String serverIP = accessor.getServerData().ip;
        String existingMetadata = ServerMetadata.getMetadata(serverIP);
        if (!existingMetadata.isBlank()) {
            customField.setValue(existingMetadata);
        }

        addCustomFieldWidget(screen);
    }


    private void addCustomFieldWidget(EditServerScreen screen) {
        if (invokeNamedWidgetAdder(screen, "addRenderableWidget", true)
                || invokeSignatureWidgetAdder(screen, EditServerScreen.class, true)
                || invokeSignatureWidgetAdder(screen, Screen.class, true)
                || invokeNamedWidgetAdder(screen, "addWidget", false)) {
            return;
        }

        // Keep the screen functional even if another mod changes method accessibility/signatures.
        SCS.LOGGER.warn("Could not attach SCS download URL field to EditServerScreen; continuing without custom field UI.");
        customField = null;
    }

    private boolean invokeNamedWidgetAdder(EditServerScreen screen, String methodName, boolean requireRenderableAttach) {
        try {
            Method method = Screen.class.getDeclaredMethod(methodName, GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, customField);
            return !requireRenderableAttach || isRenderableAttached(screen);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private boolean invokeSignatureWidgetAdder(EditServerScreen screen, Class<?> owner, boolean requireRenderableAdderSignature) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1) {
                continue;
            }
            if (!GuiEventListener.class.isAssignableFrom(parameterTypes[0])) {
                continue;
            }
            if (!GuiEventListener.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (requireRenderableAdderSignature && !isRenderableWidgetAdder(method)) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(screen, customField);
                if (!requireRenderableAdderSignature || isRenderableAttached(screen)) {
                    return true;
                }
                detachFailedWidgetAttach(screen);
            } catch (ReflectiveOperationException ignored) {
                // Try next candidate.
            }
        }
        return false;
    }


    private boolean isRenderableAttached(EditServerScreen screen) {
        ScreenCollectionsAccessor accessor = (ScreenCollectionsAccessor) (Object) screen;
        List<Renderable> renderables = accessor.scs$getRenderables();
        return renderables != null && renderables.contains(customField);
    }

    private void detachFailedWidgetAttach(EditServerScreen screen) {
        ScreenCollectionsAccessor accessor = (ScreenCollectionsAccessor) (Object) screen;
        List<Renderable> renderables = accessor.scs$getRenderables();
        List<GuiEventListener> children = accessor.scs$getChildren();
        if (renderables != null) {
            renderables.removeIf(entry -> entry == customField);
        }
        if (children != null) {
            children.removeIf(entry -> entry == customField);
        }
    }
    private boolean isRenderableWidgetAdder(Method method) {
        TypeVariable<Method>[] typeParameters = method.getTypeParameters();
        if (typeParameters.length != 1) {
            return false;
        }

        boolean hasGuiListener = false;
        boolean hasRenderable = false;
        boolean hasNarratable = false;
        for (Type bound : typeParameters[0].getBounds()) {
            if (!(bound instanceof Class<?> boundClass)) {
                continue;
            }
            if (boundClass == GuiEventListener.class) {
                hasGuiListener = true;
            } else if (boundClass == Renderable.class) {
                hasRenderable = true;
            } else if (boundClass == NarratableEntry.class) {
                hasNarratable = true;
            }
        }
        return hasGuiListener && hasRenderable && hasNarratable;
    }

    @Inject(method = "onAdd", at = @At("TAIL"))
    private void onSaveCustomField(CallbackInfo ci) {
        SCS.LOGGER.info("onSaveCustomField called");
        if (customField != null) {
            String customValue = customField.getValue();
            EditServerScreen screen = (EditServerScreen) (Object) this;
            String serverIP = ((EditServerScreenAccessor) screen).getServerData().ip;
            ServerMetadata.setMetadata(serverIP, customValue);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        int x = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - 100;
        if (labelYPositions[0] != 0) {
            graphics.drawString(Minecraft.getInstance().font, Component.translatable("screen.scs.server_name"), x, labelYPositions[0], 0xA0A0A0);
        }
        if (labelYPositions[1] != 0) {
            graphics.drawString(Minecraft.getInstance().font, Component.translatable("screen.scs.server_address"), x, labelYPositions[1], 0xA0A0A0);
        }
        graphics.drawString(Minecraft.getInstance().font, Component.translatable("screen.scs.download_url"), x, Minecraft.getInstance().getWindow().getGuiScaledHeight() / 4 + 50, 0xA0A0A0);
    }

    // Redirect original label draw call for "Server Name"
    @Redirect(method = "render", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I",
            ordinal = 0))
    private int skipNameLabel(GuiGraphics graphics, net.minecraft.client.gui.Font font, Component text, int x, int y, int color) {
        return 0;
    }

    // Redirect original label draw call for "Server Address"
    @Redirect(method = "render", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I",
            ordinal = 1))
    private int skipAddressLabel(GuiGraphics graphics, net.minecraft.client.gui.Font font, Component text, int x, int y, int color) {
        return 0;
    }
}
