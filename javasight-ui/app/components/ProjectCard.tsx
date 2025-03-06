'use client';

import { ChatBubbleLeftIcon, FolderIcon, ArchiveBoxIcon } from '@heroicons/react/24/outline'
import Link from 'next/link'
import ReactMarkdown from 'react-markdown'
import { useState } from 'react';

interface ProjectCardProps {
  id?: string
  title: string
  description: string
  modules: number
  packages: number
  files: number
  linesOfCode?: number
}

export function ProjectCard({ 
  id = "demo-project", // Default ID for demo
  title, 
  description, 
  modules, 
  packages, 
  files, 
  linesOfCode = 0 // Add default value
}: ProjectCardProps) {
  const [showFullDescription, setShowFullDescription] = useState(false);

  const handleChatClick = (e: React.MouseEvent) => {
    e.preventDefault();
    // Handle chat functionality here
  };

  const truncateDescription = (text: string = '') => {
    if (!text || text.length <= 100) return text || '';
    return `${text.slice(0, 100).trim()}... `;
  };

  return (
    <Link href={`/project/${id}`} className="block">
      <div className="relative rounded-lg border border-gray-200 bg-white p-6 shadow-sm transition-shadow hover:shadow-md">
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <ArchiveBoxIcon className="w-6 h-6 text-indigo-500" />
              <h3 className="text-xl font-semibold text-gray-900">{title}</h3>
            </div>
            <div className="mt-2 text-gray-600 prose prose-sm max-w-none">
              {showFullDescription ? (
                <div>
                  <ReactMarkdown>{description}</ReactMarkdown>
                  <button
                    onClick={(e) => {
                      e.preventDefault();
                      setShowFullDescription(false);
                    }}
                    className="text-blue-600 hover:text-blue-800 text-sm font-medium"
                  >
                    Show less
                  </button>
                </div>
              ) : (
                <div>
                  <span>{truncateDescription(description)}</span>
                  {description.length > 100 && (
                    <button
                      onClick={(e) => {
                        e.preventDefault();
                        setShowFullDescription(true);
                      }}
                      className="text-blue-600 hover:text-blue-800 text-sm font-medium inline"
                    >
                      more
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>
          <button 
            className="rounded-full p-2 hover:bg-gray-100 ml-4"
            onClick={handleChatClick}
          >
            <ChatBubbleLeftIcon className="h-5 w-5 text-gray-600" />
          </button>
        </div>
        
        <div className="mt-4 flex gap-4 text-sm text-gray-600">
          <div>
            <span className="font-medium">{modules}</span> modules
          </div>
          <div>
            <span className="font-medium">{packages}</span> packages
          </div>
          <div>
            <span className="font-medium">{files}</span> files
          </div>
          <div>
            <span className="font-medium">{linesOfCode?.toLocaleString() || '0'}</span> lines of code
          </div>
        </div>
      </div>
    </Link>
  )
} 