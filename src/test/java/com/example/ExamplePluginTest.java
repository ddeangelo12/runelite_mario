package com.example;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import sm64.MarioPlugin;

public class ExamplePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ExamplePlugin.class, MarioPlugin.class);
		RuneLite.main(args);
	}
}