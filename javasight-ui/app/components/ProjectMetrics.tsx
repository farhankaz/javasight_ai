'use client';

import { useEffect, useState } from 'react';
import { FileIcon, CodeIcon, PackageIcon, ModuleIcon } from '@heroicons/react/24/outline';

interface MetricCardProps {
  title: string;
  value: string | number;
  icon?: React.ComponentType<{ className?: string }>;
}

function MetricCard({ title, value, icon: Icon }: MetricCardProps) {
  return (
    <div className="bg-white rounded-lg p-6 shadow-sm flex items-center">
      {Icon && <Icon className="w-6 h-6 text-gray-400 mr-4" />}
      <div>
        <h3 className="text-gray-600 text-sm mb-2">{title}</h3>
        <p className="text-4xl font-semibold text-indigo-600">{value}</p>
      </div>
    </div>
  );
}

interface Metrics {
    moduleCount?: number;
    packageCount?: number;
    fileCount?: number;
    linesOfCode?: number;
}

interface ProjectMetricsProps {
    metrics: Metrics | null;
}

export default function ProjectMetrics({ metrics }: ProjectMetricsProps) {
    if (!metrics) {
        return null;
    }

    return (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
            <div className="bg-white p-4 rounded-lg shadow">
                <h3 className="text-sm font-medium text-gray-500">Modules</h3>
                <p className="text-2xl font-semibold">{metrics.moduleCount || 0}</p>
            </div>
            <div className="bg-white p-4 rounded-lg shadow">
                <h3 className="text-sm font-medium text-gray-500">Packages</h3>
                <p className="text-2xl font-semibold">{metrics.packageCount || 0}</p>
            </div>
            <div className="bg-white p-4 rounded-lg shadow">
                <h3 className="text-sm font-medium text-gray-500">Files</h3>
                <p className="text-2xl font-semibold">{metrics.fileCount || 0}</p>
            </div>
            <div className="bg-white p-4 rounded-lg shadow">
                <h3 className="text-sm font-medium text-gray-500">Lines of Code</h3>
                <p className="text-2xl font-semibold">{metrics.linesOfCode?.toLocaleString() || 0}</p>
            </div>
        </div>
    )
} 