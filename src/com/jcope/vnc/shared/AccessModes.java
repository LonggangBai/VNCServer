package com.jcope.vnc.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import com.jcope.vnc.server.SecurityPolicy;

public class AccessModes
{
    public static enum ACCESS_MODE
    {
        VIEW_ONLY,
        VIEW_AND_CHAT,
        FULL_CONTROL,
        ALL
        
        ;
        
        private static ACCESS_MODE[] sorted(ACCESS_MODE exclude)
        {
            ACCESS_MODE[] rval;
            
            int size;
            ACCESS_MODE[] values = ACCESS_MODE.values();
            HashMap<String, ACCESS_MODE> nameToEnum = new HashMap<String, ACCESS_MODE>(values.length);
            ArrayList<String> names = new ArrayList<String>(values.length);
            for (ACCESS_MODE value : values)
            {
                if (exclude == null || exclude != value)
                {
                    String name = value.commonName();
                    
                    names.add(name);
                    nameToEnum.put(name, value);
                }
            }
            rval = new ACCESS_MODE[values.length - (exclude == null ? 0 : 1)];
            Collections.sort(names);
            size = names.size();
            for (int i=0; i<size; i++)
            {
                rval[i] = nameToEnum.get(names.get(i));
            }
            
            return rval;
        }
        
        public static ACCESS_MODE[] sorted()
        {
            if (AccessModesCache.sortedList == null)
            {
                AccessModesCache.sortedList = sorted(null);
            }
            
            return AccessModesCache.sortedList;
        }
        
        public static ACCESS_MODE[] selectable()
        {
            if (AccessModesCache.selectableList == null)
            {
                AccessModesCache.selectableList = sorted(ACCESS_MODE.ALL);
            }
            
            return AccessModesCache.selectableList;
        }
        
        public String commonName()
        {
            String rval = equals(ALL) ? SecurityPolicy.ALL_TOKEN : name();
            
            return rval;
        }

        public static ACCESS_MODE get(String accessModeStr)
        {
            ACCESS_MODE rval;
            
            if (AccessModesCache.modesByName == null)
            {
                ACCESS_MODE[] values = ACCESS_MODE.values();
                AccessModesCache.modesByName = new HashMap<String, ACCESS_MODE>(values.length);
                for (ACCESS_MODE value : values)
                {
                    String name = value.commonName();
                    AccessModesCache.modesByName.put(name, value);
                }
            }
            
            rval = AccessModesCache.modesByName.get(accessModeStr);
            
            return rval;
        }
    };
}