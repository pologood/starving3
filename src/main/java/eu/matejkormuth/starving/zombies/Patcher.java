/**
 * Starving - Bukkit API server mod with Zombies.
 * Copyright (c) 2015, Matej Kormuth <http://www.github.com/dobrakmato>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package eu.matejkormuth.starving.zombies;

import eu.matejkormuth.starving.main.NMSHooks;
import net.minecraft.server.v1_8_R3.BiomeBase;
import net.minecraft.server.v1_8_R3.EntityInsentient;
import net.minecraft.server.v1_8_R3.EntityTypes;
import net.minecraft.server.v1_8_R3.EntityZombie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@NMSHooks(version = "v1_8_R3")
public class Patcher {

    private static final Logger log = LoggerFactory.getLogger(Patcher.class);

    public void patchAll() {
        patchZombies();
    }

    private void patchZombies() {
        log.info("Patching Zombie in EntityTypes class.");
        patchEntity(Zombie.class, "Zombie", 54);
        log.info("Patching Zombie in BiomeBase class.");
        patchBiomeBase(EntityZombie.class, Zombie.class);
    }

    @SuppressWarnings("unchecked")
    private void patchBiomeBase(Class<? extends EntityInsentient> previousEntityClass, Class<? extends EntityInsentient> entityClass) {
        for (Field f : BiomeBase.class.getDeclaredFields()) {
            if (f.getType().equals(BiomeBase.class)) {
                if (!f.isAccessible()) {
                    f.setAccessible(true);
                }
                try {
                    List<BiomeBase.BiomeMeta> monsterSpawnList = (List<BiomeBase.BiomeMeta>) getField("at", BiomeBase.class, f.get(null));
                    for (Iterator<BiomeBase.BiomeMeta> itr = monsterSpawnList.iterator(); itr.hasNext(); ) {
                        BiomeBase.BiomeMeta meta = itr.next();
                        if (meta.b.equals(previousEntityClass)) {
                            itr.remove();
                        }
                    }
                    monsterSpawnList.add(new BiomeBase.BiomeMeta(entityClass, 100, 4, 4));
                } catch (IllegalAccessException e) {
                    log.error("Can't patch Zombie to BiomeBase!", e);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void patchEntity(Class<?> entityClass, String name, int id) {
        // According to some online resource, we must put values in 'd' and 'f'
        // fields.
        ((Map) getField("d", EntityTypes.class, null)).put(entityClass, name);
        ((Map) getField("f", EntityTypes.class, null)).put(entityClass, Integer.valueOf(id));
    }

    private Object getField(String fieldName, Class<?> clazz, Object object) {
        try {
            Field f = clazz.getDeclaredField(fieldName);

            if (!f.isAccessible()) {
                f.setAccessible(true);
            }

            return f.get(object);
        } catch (NoSuchFieldException | SecurityException
                | IllegalArgumentException | IllegalAccessException e) {
            log.error("Can't get field {} of class {} on object {} because: {}!", fieldName, clazz.getName(), object, e);
            return null;
        }

    }
}
