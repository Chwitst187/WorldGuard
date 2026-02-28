/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Scheduler wrapper that uses Folia schedulers when available and falls back to Bukkit's scheduler otherwise.
 */
public class TaskSchedulerAdapter {

    private final JavaPlugin plugin;
    private final List<Object> tasks = Collections.synchronizedList(new ArrayList<>());
    private final boolean folia;
    private final Object globalRegionScheduler;
    private final Object regionScheduler;

    public TaskSchedulerAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
        Server server = Bukkit.getServer();

        this.folia = hasMethod(server.getClass(), "getGlobalRegionScheduler");
        this.globalRegionScheduler = folia ? invoke(server, "getGlobalRegionScheduler") : null;
        this.regionScheduler = folia ? invoke(server, "getRegionScheduler") : null;
    }

    public Object runGlobal(Runnable runnable) {
        if (!folia) {
            BukkitTask task = Bukkit.getScheduler().runTask(plugin, runnable);
            tasks.add(task);
            return task;
        }

        Object task = invoke(globalRegionScheduler, "run", plugin, asConsumer(runnable));
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    public Object runGlobalRepeating(Runnable runnable, long delayTicks, long periodTicks) {
        if (!folia) {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
            tasks.add(task);
            return task;
        }

        Object task = invoke(globalRegionScheduler, "runAtFixedRate", plugin, asConsumer(runnable), delayTicks, periodTicks);
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    public Object runAtEntityDelayed(Entity entity, Runnable runnable, long delayTicks) {
        if (!folia) {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
            tasks.add(task);
            return task;
        }

        Object entityScheduler = invoke(entity, "getScheduler");
        if (entityScheduler == null) {
            return runGlobal(runnable);
        }

        Object task = invoke(entityScheduler, "runDelayed", plugin, asConsumer(runnable), null, delayTicks);
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    public Object runAtLocationDelayed(Location location, Runnable runnable, long delayTicks) {
        if (!folia) {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
            tasks.add(task);
            return task;
        }

        Object task = invoke(regionScheduler, "runDelayed", plugin, location.getWorld(), location.getBlockX(), location.getBlockZ(), asConsumer(runnable), delayTicks);
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    public void cancelAll() {
        synchronized (tasks) {
            for (Object task : tasks) {
                if (task instanceof BukkitTask bukkitTask) {
                    bukkitTask.cancel();
                } else {
                    invoke(task, "cancel");
                }
            }
            tasks.clear();
        }

        if (!folia) {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private Consumer<Object> asConsumer(Runnable runnable) {
        return ignored -> runnable.run();
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }

        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }

            try {
                return method.invoke(target, args);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // Try the next overload.
            }
        }

        return null;
    }
}

