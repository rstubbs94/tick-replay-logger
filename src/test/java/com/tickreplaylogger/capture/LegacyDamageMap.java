/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/** Test-only attack lookup for the frozen interpreter; production uses compiled resolution rules. */
final class LegacyDamageMap extends DamageMap
{
    private PreRuleIrDamageMap attacks;
    private Map<String,Map<String,PreRuleIrDamageMap.AttackRule>> overrides;

    static LegacyDamageMap from(DamageMap source)
    {
        if(source instanceof LegacyDamageMap)
        {
            return (LegacyDamageMap)source;
        }
        LegacyDamageMap result=new LegacyDamageMap();
        try
        {
            for(Field field:DamageMap.class.getDeclaredFields())
            {
                if(!Modifier.isStatic(field.getModifiers()))
                {
                    field.setAccessible(true);
                    field.set(result,field.get(source));
                }
            }
        }
        catch(ReflectiveOperationException failure)
        {
            throw new AssertionError("Cannot copy factual capture lookups for the frozen oracle",failure);
        }
        return result;
    }

    void attackRules(Map<String,Map<String,PreRuleIrDamageMap.AttackRule>> rules)
    {
        overrides=rules;
        attacks=null;
    }

    PreRuleIrDamageMap.AttackRule attackRuleForNpc(int id,int animation,Integer level,boolean normal)
    {
        if(attacks==null)
        {
            attacks=PreRuleIrDamageMap.load(new Gson(),LegacyCombatCatalog.from(catalog()));
            if(overrides!=null)
            {
                try
                {
                    Field field=PreRuleIrDamageMap.class.getDeclaredField("npcAttackRules");
                    field.setAccessible(true);
                    field.set(attacks,overrides);
                }
                catch(ReflectiveOperationException failure)
                {
                    throw new AssertionError("Cannot install isolated frozen-oracle fixture",failure);
                }
            }
        }
        return attacks.attackRuleForNpc(id,animation,level,normal);
    }
}
