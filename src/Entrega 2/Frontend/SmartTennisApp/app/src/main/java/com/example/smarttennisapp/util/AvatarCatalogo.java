package com.example.smarttennisapp.util;

import com.example.smarttennisapp.R;

/** Catalogo local de avatares pre-selecionados; nao depende de rede. */
public final class AvatarCatalogo {
    public static final String DEFAULT_ID = "avatar_01";
    private static final String[] IDS = {
            "avatar_01", "avatar_02", "avatar_03", "avatar_04",
            "avatar_05", "avatar_06", "avatar_07", "avatar_08",
            "avatar_09", "avatar_10", "avatar_11", "avatar_12"
    };
    private static final int[] DRAWABLES = {
            R.drawable.avatar_01, R.drawable.avatar_02, R.drawable.avatar_03, R.drawable.avatar_04,
            R.drawable.avatar_05, R.drawable.avatar_06, R.drawable.avatar_07, R.drawable.avatar_08,
            R.drawable.avatar_09, R.drawable.avatar_10, R.drawable.avatar_11, R.drawable.avatar_12
    };

    private AvatarCatalogo() { }

    public static String[] ids() { return IDS.clone(); }

    public static int drawableId(String avatarId) {
        if (avatarId != null) {
            for (int i = 0; i < IDS.length; i++) {
                if (IDS[i].equals(avatarId)) return DRAWABLES[i];
            }
        }
        return DRAWABLES[0];
    }
}
